ALTER TABLE function_page_bindings
    ADD COLUMN IF NOT EXISTS second_best_score double precision NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS score_margin double precision NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS policy_version varchar(64) NOT NULL DEFAULT 'legacy',
    ADD COLUMN IF NOT EXISTS decision_source varchar(32) NOT NULL DEFAULT 'rule',
    ADD COLUMN IF NOT EXISTS reviewed_by varchar(120) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS reviewed_at timestamptz,
    ADD COLUMN IF NOT EXISTS inherited_from uuid;

ALTER TABLE function_action_bindings
    ADD COLUMN IF NOT EXISTS second_best_score double precision NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS score_margin double precision NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS policy_version varchar(64) NOT NULL DEFAULT 'legacy',
    ADD COLUMN IF NOT EXISTS decision_source varchar(32) NOT NULL DEFAULT 'rule',
    ADD COLUMN IF NOT EXISTS reviewed_by varchar(120) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS reviewed_at timestamptz,
    ADD COLUMN IF NOT EXISTS inherited_from uuid;

CREATE INDEX IF NOT EXISTS ix_function_page_bindings_function_status
    ON function_page_bindings (run_id, function_id, review_status);

CREATE INDEX IF NOT EXISTS ix_function_action_bindings_function_status
    ON function_action_bindings (run_id, function_id, review_status);
