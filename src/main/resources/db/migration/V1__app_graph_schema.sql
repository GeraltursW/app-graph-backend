CREATE TABLE IF NOT EXISTS apps (
    app_id uuid PRIMARY KEY,
    package_name varchar(255) NOT NULL UNIQUE,
    app_name varchar(255) NOT NULL,
    market_rank integer,
    category varchar(120),
    platform varchar(32) NOT NULL DEFAULT 'android',
    vendor varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS scans (
    scan_id uuid PRIMARY KEY,
    app_id uuid NOT NULL REFERENCES apps(app_id),
    app_version varchar(120),
    device_id varchar(255),
    platform varchar(32) NOT NULL DEFAULT 'android',
    os_version varchar(120),
    script_version varchar(120),
    ai_model varchar(120),
    status varchar(32) NOT NULL DEFAULT 'running',
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    error_message text,
    scan_config jsonb
);

CREATE TABLE IF NOT EXISTS assets (
    asset_id uuid PRIMARY KEY,
    scan_id uuid REFERENCES scans(scan_id),
    app_id uuid NOT NULL REFERENCES apps(app_id),
    asset_type varchar(64) NOT NULL,
    storage_url varchar(1024),
    local_path varchar(1024),
    sha256 varchar(64) NOT NULL UNIQUE,
    width integer,
    height integer,
    mime_type varchar(120),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS canonical_pages (
    canonical_page_id uuid PRIMARY KEY,
    app_id uuid NOT NULL REFERENCES apps(app_id),
    canonical_page_key varchar(255) NOT NULL,
    page_hash_id varchar(64) UNIQUE,
    display_name varchar(255) NOT NULL,
    page_type varchar(120) NOT NULL,
    representative_asset_id uuid REFERENCES assets(asset_id),
    primary_structure_hash varchar(64),
    primary_visual_hash varchar(128),
    primary_route_hash varchar(64),
    instance_count integer NOT NULL DEFAULT 0,
    review_status varchar(32) NOT NULL DEFAULT 'pending',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS page_instances (
    page_instance_id uuid PRIMARY KEY,
    scan_id uuid NOT NULL REFERENCES scans(scan_id),
    app_id uuid NOT NULL REFERENCES apps(app_id),
    canonical_page_id uuid REFERENCES canonical_pages(canonical_page_id),
    page_title varchar(255) NOT NULL,
    page_type varchar(120) NOT NULL,
    screenshot_asset_id uuid REFERENCES assets(asset_id),
    screenshot_hash varchar(64),
    visual_hash varchar(128),
    structure_hash varchar(64),
    route_hash varchar(64),
    ocr_text text,
    ai_summary text,
    inferred_purpose text,
    page_url text,
    images jsonb NOT NULL DEFAULT '[]'::jsonb,
    action jsonb,
    ai_inference jsonb NOT NULL DEFAULT '{}'::jsonb,
    ai_recursive boolean NOT NULL DEFAULT false,
    confidence numeric(4,3),
    raw_ai_payload jsonb,
    normalized_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS page_edges (
    edge_id uuid PRIMARY KEY,
    scan_id uuid NOT NULL REFERENCES scans(scan_id),
    app_id uuid NOT NULL REFERENCES apps(app_id),
    from_page_instance_id uuid REFERENCES page_instances(page_instance_id),
    to_page_instance_id uuid REFERENCES page_instances(page_instance_id),
    from_canonical_page_id uuid REFERENCES canonical_pages(canonical_page_id),
    to_canonical_page_id uuid REFERENCES canonical_pages(canonical_page_id),
    widget_id uuid,
    action_type varchar(64) NOT NULL DEFAULT 'tap',
    label varchar(255) NOT NULL,
    widget_description text,
    confidence numeric(4,3),
    status varchar(32) NOT NULL DEFAULT 'discovered',
    raw_action_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS page_actions (
    action_id uuid PRIMARY KEY,
    app_id uuid NOT NULL REFERENCES apps(app_id),
    canonical_page_id uuid NOT NULL REFERENCES canonical_pages(canonical_page_id),
    page_instance_id uuid REFERENCES page_instances(page_instance_id),
    edge_id uuid REFERENCES page_edges(edge_id),
    action_fingerprint varchar(64) NOT NULL,
    action_layer varchar(32) NOT NULL,
    action_type varchar(64) NOT NULL DEFAULT 'tap',
    semantic_name varchar(255) NOT NULL,
    description text NOT NULL DEFAULT '',
    target jsonb NOT NULL DEFAULT '{}'::jsonb,
    parameters jsonb NOT NULL DEFAULT '{}'::jsonb,
    expected_effect jsonb NOT NULL DEFAULT '{}'::jsonb,
    effect_status varchar(32) NOT NULL DEFAULT 'predicted',
    confidence double precision,
    raw_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_page_action_fingerprint UNIQUE (canonical_page_id, action_fingerprint)
);

CREATE TABLE IF NOT EXISTS embedding_records (
    embedding_id uuid PRIMARY KEY,
    app_id uuid NOT NULL REFERENCES apps(app_id),
    owner_type varchar(64) NOT NULL,
    owner_id uuid NOT NULL,
    model_name varchar(120) NOT NULL,
    content_hash varchar(64) NOT NULL,
    embedding text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_apps_name ON apps (lower(app_name));
CREATE INDEX IF NOT EXISTS ix_canonical_pages_app ON canonical_pages (app_id);
CREATE INDEX IF NOT EXISTS ix_page_instances_app_page ON page_instances (app_id, canonical_page_id);
CREATE INDEX IF NOT EXISTS ix_page_edges_app_from_to ON page_edges (app_id, from_canonical_page_id, to_canonical_page_id);
CREATE INDEX IF NOT EXISTS ix_page_actions_page_layer ON page_actions (canonical_page_id, action_layer);
