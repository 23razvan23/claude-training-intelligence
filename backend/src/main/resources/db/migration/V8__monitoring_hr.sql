-- All-day heart rate from Garmin monitoring FIT files (raw, append-only), and the
-- daily background load derived from it. Whoop's day strain counts all-day cardio
-- load, not only workouts; without this, strain cannot be calibrated (ADR 0007).

CREATE TABLE monitoring_hr (
    athlete_id       BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    ts               TIMESTAMPTZ NOT NULL,
    hr               SMALLINT    NOT NULL,
    import_batch_id  BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    PRIMARY KEY (athlete_id, ts)
);
SELECT create_hypertable('monitoring_hr', by_range('ts', INTERVAL '30 days'));

ALTER TABLE day_features
    ADD COLUMN background_load    NUMERIC(8, 2),  -- Banister load from all-day HR outside workouts
    ADD COLUMN monitored_minutes  INT;            -- minutes of all-day HR coverage that day
