CREATE TABLE IF NOT EXISTS function_catalogs (
    catalog_id uuid PRIMARY KEY,
    app_id uuid NOT NULL REFERENCES apps(app_id),
    app_version varchar(120),
    source varchar(120) NOT NULL DEFAULT 'vendor',
    vendor_version varchar(120) NOT NULL,
    schema_version varchar(32) NOT NULL DEFAULT '1.0',
    status varchar(32) NOT NULL DEFAULT 'active',
    raw_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    raw_tree jsonb NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_function_catalog_version UNIQUE (app_id, source, vendor_version)
);

CREATE TABLE IF NOT EXISTS function_nodes (
    function_id uuid PRIMARY KEY,
    catalog_id uuid NOT NULL REFERENCES function_catalogs(catalog_id) ON DELETE CASCADE,
    vendor_function_id varchar(255) NOT NULL,
    parent_function_id uuid REFERENCES function_nodes(function_id),
    level integer NOT NULL DEFAULT 1,
    name varchar(255) NOT NULL,
    description text NOT NULL DEFAULT '',
    function_path text NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    automation_limited boolean NOT NULL DEFAULT false,
    features jsonb NOT NULL DEFAULT '[]'::jsonb,
    expected_capabilities jsonb NOT NULL DEFAULT '[]'::jsonb,
    match_rules jsonb NOT NULL DEFAULT '{}'::jsonb,
    raw_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_catalog_vendor_function UNIQUE (catalog_id, vendor_function_id)
);

CREATE TABLE IF NOT EXISTS function_match_runs (
    run_id uuid PRIMARY KEY,
    catalog_id uuid NOT NULL REFERENCES function_catalogs(catalog_id),
    app_id uuid NOT NULL REFERENCES apps(app_id),
    graph_version varchar(120) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'running',
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_message text,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE IF NOT EXISTS function_page_bindings (
    binding_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES function_match_runs(run_id) ON DELETE CASCADE,
    function_id uuid NOT NULL REFERENCES function_nodes(function_id),
    canonical_page_id uuid NOT NULL REFERENCES canonical_pages(canonical_page_id),
    binding_source varchar(32) NOT NULL DEFAULT 'rule',
    match_score double precision NOT NULL,
    match_evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    review_status varchar(32) NOT NULL DEFAULT 'suggested',
    is_primary boolean NOT NULL DEFAULT false,
    operator_note text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_run_function_page UNIQUE (run_id, function_id, canonical_page_id)
);

CREATE TABLE IF NOT EXISTS function_action_bindings (
    binding_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES function_match_runs(run_id) ON DELETE CASCADE,
    function_id uuid NOT NULL REFERENCES function_nodes(function_id),
    action_id uuid NOT NULL REFERENCES page_actions(action_id),
    capability_role varchar(32) NOT NULL DEFAULT 'behavior',
    binding_source varchar(32) NOT NULL DEFAULT 'rule',
    match_score double precision NOT NULL,
    match_evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    review_status varchar(32) NOT NULL DEFAULT 'suggested',
    is_primary boolean NOT NULL DEFAULT false,
    operator_note text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_run_function_action UNIQUE (run_id, function_id, action_id)
);

CREATE TABLE IF NOT EXISTS test_case_batches (
    batch_id uuid PRIMARY KEY,
    app_id uuid NOT NULL REFERENCES apps(app_id),
    run_id uuid REFERENCES function_match_runs(run_id),
    generation_mode varchar(32) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'generated',
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS test_cases (
    test_case_id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES test_case_batches(batch_id) ON DELETE CASCADE,
    app_id uuid NOT NULL REFERENCES apps(app_id),
    case_type varchar(32) NOT NULL,
    name varchar(255) NOT NULL,
    start_page_id uuid REFERENCES canonical_pages(canonical_page_id),
    terminal_page_id uuid REFERENCES canonical_pages(canonical_page_id),
    function_id uuid REFERENCES function_nodes(function_id),
    steps jsonb NOT NULL DEFAULT '[]'::jsonb,
    collection_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
    expected_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'draft',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_function_nodes_catalog_parent ON function_nodes (catalog_id, parent_function_id);
CREATE INDEX IF NOT EXISTS ix_match_runs_catalog ON function_match_runs (catalog_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_function_page_bindings_review ON function_page_bindings (run_id, review_status);
CREATE INDEX IF NOT EXISTS ix_function_action_bindings_review ON function_action_bindings (run_id, review_status);
CREATE INDEX IF NOT EXISTS ix_test_cases_batch_type ON test_cases (batch_id, case_type);

