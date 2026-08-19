CREATE TABLE IF NOT EXISTS test_execution_runs (
    run_id uuid PRIMARY KEY,
    test_case_id uuid NOT NULL REFERENCES test_cases(test_case_id),
    app_id uuid NOT NULL REFERENCES apps(app_id),
    trigger_source varchar(64) NOT NULL DEFAULT 'manual',
    alert_id varchar(255),
    alert_url text,
    device_info jsonb NOT NULL DEFAULT '{}'::jsonb,
    environment jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(32) NOT NULL DEFAULT 'running',
    score integer,
    verdict varchar(64),
    diagnosis text NOT NULL DEFAULT '',
    summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS test_execution_steps (
    step_result_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES test_execution_runs(run_id) ON DELETE CASCADE,
    step_no integer NOT NULL,
    stage varchar(32) NOT NULL,
    title varchar(255) NOT NULL,
    action text NOT NULL DEFAULT '',
    status varchar(32) NOT NULL,
    duration_ms bigint NOT NULL DEFAULT 0,
    before_image text,
    after_image text,
    ai_observation text NOT NULL DEFAULT '',
    raw_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_execution_run_step UNIQUE (run_id, step_no)
);

CREATE TABLE IF NOT EXISTS test_metric_results (
    metric_result_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES test_execution_runs(run_id) ON DELETE CASCADE,
    metric_name varchar(120) NOT NULL,
    baseline_value double precision,
    actual_value double precision NOT NULL,
    unit varchar(32) NOT NULL DEFAULT '',
    threshold_value double precision,
    comparison varchar(16) NOT NULL DEFAULT 'lower',
    status varchar(32) NOT NULL,
    sample_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_execution_run_metric UNIQUE (run_id, metric_name)
);

CREATE INDEX IF NOT EXISTS ix_execution_runs_app_created ON test_execution_runs (app_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_execution_runs_alert_url ON test_execution_runs (app_id, alert_url);
CREATE INDEX IF NOT EXISTS ix_execution_steps_run ON test_execution_steps (run_id, step_no);
CREATE INDEX IF NOT EXISTS ix_metric_results_run ON test_metric_results (run_id, status);
