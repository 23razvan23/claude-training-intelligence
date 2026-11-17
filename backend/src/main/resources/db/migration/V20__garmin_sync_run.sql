-- Garmin daily sync (stage 1b-4 PR 2, ADR 0015 D6-D8).

-- One row per sync run: what was asked, how it ended, and when it retries. Holds no Garmin data
-- (that goes to the raw tables through an import batch of kind GARMIN_SYNC) and no secret.
CREATE TABLE garmin_sync_run (
    id              BIGSERIAL   PRIMARY KEY,
    athlete_id      BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    trigger         TEXT        NOT NULL CHECK (trigger IN ('MANUAL', 'MORNING')),
    status          TEXT        NOT NULL CHECK (status IN ('RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')),
    attempt         SMALLINT    NOT NULL DEFAULT 1,
    first_day       DATE        NOT NULL,
    last_day        DATE        NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    next_retry_at   TIMESTAMPTZ,
    error_code      TEXT,       -- a fixed code (ADR 0011 D9), never a Garmin payload
    import_batch_id BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    rows_inserted   BIGINT,
    recomputed      BOOLEAN     NOT NULL DEFAULT false,
    missing         JSONB       -- labels of the parts Garmin had no data for, e.g. "hrv:2026-11-17"
);
CREATE INDEX garmin_sync_run_athlete ON garmin_sync_run (athlete_id, started_at DESC);
-- At most one run per athlete is running or waiting to retry.
CREATE UNIQUE INDEX garmin_sync_run_one_active ON garmin_sync_run (athlete_id) WHERE status IN ('RUNNING', 'RETRY_WAIT');

-- Who started a Recompute features run: the coach's button ('manual') or a sync ('garmin-sync').
-- The Holdout tab marks only manual runs (ADR 0015 D8); every earlier run was manual.
ALTER TABLE feature_recompute_log
    ADD COLUMN source TEXT NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'garmin-sync'));
