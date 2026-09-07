ALTER TABLE apps ADD COLUMN graph_version bigint NOT NULL DEFAULT 0;
ALTER TABLE page_instances ADD COLUMN embedding_text text;
UPDATE page_instances SET embedding_text = coalesce(raw_ai_payload->>'embeddingText', raw_ai_payload->>'embedding_text');

CREATE TABLE app_url_coverage_fact (
  app_id uuid NOT NULL REFERENCES apps(app_id),
  page_url_hash text NOT NULL,
  page_url text NOT NULL,
  first_coverage_time timestamptz,
  is_baseline boolean NOT NULL DEFAULT false,
  PRIMARY KEY (app_id, page_url_hash)
);
CREATE INDEX ix_coverage_time ON app_url_coverage_fact(first_coverage_time) WHERE NOT is_baseline;
INSERT INTO app_url_coverage_fact(app_id,page_url_hash,page_url,is_baseline)
SELECT DISTINCT app_id,encode(sha256(convert_to(page_url,'UTF8')),'hex'),page_url,true
FROM page_instances WHERE nullif(btrim(embedding_text),'') IS NOT NULL AND nullif(btrim(page_url),'') IS NOT NULL;

CREATE TABLE page_coverage_event (
  event_id bigserial PRIMARY KEY, app_id uuid NOT NULL,
  page_instance_id uuid NOT NULL, page_url text NOT NULL,
  event_type text NOT NULL, recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION record_page_coverage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE url_hash text;
BEGIN
  IF nullif(btrim(NEW.embedding_text),'') IS NOT NULL AND nullif(btrim(NEW.page_url),'') IS NOT NULL THEN
    url_hash := encode(sha256(convert_to(NEW.page_url,'UTF8')),'hex');
    INSERT INTO app_url_coverage_fact(app_id,page_url_hash,page_url,first_coverage_time)
    VALUES(NEW.app_id,url_hash,NEW.page_url,clock_timestamp()) ON CONFLICT DO NOTHING;
    IF EXISTS(SELECT 1 FROM app_url_coverage_fact WHERE app_id=NEW.app_id AND page_url_hash=url_hash AND page_url<>NEW.page_url) THEN
      RAISE EXCEPTION 'URL hash collision';
    END IF;
    IF TG_OP='INSERT' THEN
      INSERT INTO page_coverage_event(app_id,page_instance_id,page_url,event_type) VALUES(NEW.app_id,NEW.page_instance_id,NEW.page_url,'COVERED');
    ELSIF NEW.page_url IS DISTINCT FROM OLD.page_url OR NEW.app_id IS DISTINCT FROM OLD.app_id OR nullif(btrim(OLD.embedding_text),'') IS NULL THEN
      INSERT INTO page_coverage_event(app_id,page_instance_id,page_url,event_type) VALUES(NEW.app_id,NEW.page_instance_id,NEW.page_url,'COVERED');
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_page_coverage AFTER INSERT OR UPDATE OF embedding_text,page_url,app_id ON page_instances
FOR EACH ROW EXECUTE FUNCTION record_page_coverage();

CREATE TABLE graph_entry_points (
  app_id uuid NOT NULL REFERENCES apps(app_id),
  page_id uuid PRIMARY KEY REFERENCES canonical_pages(canonical_page_id) ON DELETE CASCADE,
  entry_kind text NOT NULL CHECK(entry_kind IN ('APP_HOME','DEEPLINK','NOTIFICATION','SYSTEM_INTENT','EXTERNAL_APP')),
  evidence text NOT NULL
);
INSERT INTO graph_entry_points(app_id,page_id,entry_kind,evidence)
SELECT p.app_id,p.canonical_page_id,'APP_HOME','Historical root baseline'
FROM canonical_pages p WHERE p.page_type<>'orphan'
AND NOT EXISTS(SELECT 1 FROM page_edges e WHERE e.to_canonical_page_id=p.canonical_page_id);

CREATE TABLE graph_pending_entries (
  page_id uuid PRIMARY KEY REFERENCES canonical_pages(canonical_page_id) ON DELETE CASCADE,
  reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE graph_operation_batches (
  request_id uuid PRIMARY KEY, app_id uuid NOT NULL REFERENCES apps(app_id),
  request_body jsonb NOT NULL, response_body jsonb NOT NULL,
  changes jsonb NOT NULL, graph_version bigint NOT NULL,
  rolled_back boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION guard_graph_edge() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM apps WHERE app_id=NEW.app_id FOR UPDATE;
  IF TG_OP='UPDATE' AND NEW.app_id<>OLD.app_id THEN RAISE EXCEPTION 'Cannot change edge app'; END IF;
  IF NEW.from_canonical_page_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM canonical_pages WHERE canonical_page_id=NEW.from_canonical_page_id AND app_id=NEW.app_id) THEN RAISE EXCEPTION 'Source app mismatch'; END IF;
  IF NEW.to_canonical_page_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM canonical_pages WHERE canonical_page_id=NEW.to_canonical_page_id AND app_id=NEW.app_id) THEN RAISE EXCEPTION 'Target app mismatch'; END IF;
  IF NEW.from_canonical_page_id=NEW.to_canonical_page_id OR EXISTS(
    WITH RECURSIVE descendants(id) AS (
      SELECT NEW.to_canonical_page_id
      UNION
      SELECT e.to_canonical_page_id FROM page_edges e JOIN descendants d ON e.from_canonical_page_id=d.id
      WHERE e.app_id=NEW.app_id AND e.edge_id<>NEW.edge_id
    ) SELECT 1 FROM descendants WHERE id=NEW.from_canonical_page_id
  ) THEN RAISE EXCEPTION 'Graph cycle is forbidden' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_graph_dag BEFORE INSERT OR UPDATE ON page_edges FOR EACH ROW EXECUTE FUNCTION guard_graph_edge();
CREATE FUNCTION bump_graph_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN UPDATE apps SET graph_version=graph_version+1 WHERE app_id=OLD.app_id; RETURN OLD; END IF;
  UPDATE apps SET graph_version=graph_version+1 WHERE app_id=NEW.app_id; RETURN NEW;
END $$;
CREATE TRIGGER trg_edge_version AFTER INSERT OR UPDATE OR DELETE ON page_edges FOR EACH ROW EXECUTE FUNCTION bump_graph_version();
CREATE TRIGGER trg_node_version AFTER INSERT OR UPDATE OR DELETE ON canonical_pages FOR EACH ROW EXECUTE FUNCTION bump_graph_version();
CREATE TRIGGER trg_entry_version AFTER INSERT OR UPDATE OR DELETE ON graph_entry_points FOR EACH ROW EXECUTE FUNCTION bump_graph_version();

CREATE TABLE report_baselines (
  revision bigserial PRIMARY KEY, request_id uuid NOT NULL UNIQUE,
  source text NOT NULL, payload jsonb NOT NULL, published_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE graph_daily_reports (
  report_id uuid PRIMARY KEY, request_id uuid NOT NULL UNIQUE,
  request_body jsonb NOT NULL, report_type text NOT NULL,
  report_date date NOT NULL, baseline_revision bigint NOT NULL REFERENCES report_baselines(revision),
  payload jsonb NOT NULL, generated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_report_period ON graph_daily_reports(report_date,report_type,generated_at DESC);
