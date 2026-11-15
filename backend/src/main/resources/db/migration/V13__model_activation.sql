-- R-04: record when a model version was activated (and by whom) and when it was retired.
-- Stage 1b-3 (live comparison with Whoop) starts from the ACTIVE version's activated_at.
-- Existing rows are left as they are: a version that was already ACTIVE before this
-- migration keeps a NULL activated_at, and the UI says "activation time unknown".
ALTER TABLE model_version
    ADD COLUMN activated_at TIMESTAMPTZ,
    ADD COLUMN activated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN retired_at   TIMESTAMPTZ;
