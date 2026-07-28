ALTER TABLE IF EXISTS function_page_bindings
    ADD COLUMN IF NOT EXISTS operator_note text NOT NULL DEFAULT '';

ALTER TABLE IF EXISTS function_action_bindings
    ADD COLUMN IF NOT EXISTS operator_note text NOT NULL DEFAULT '';

ALTER TABLE IF EXISTS function_action_bindings
    ADD COLUMN IF NOT EXISTS is_primary boolean NOT NULL DEFAULT false;
