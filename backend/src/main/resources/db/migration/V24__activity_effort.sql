-- Stage 2-1b follow-up (ADR 0020 amendment): the effort fields of a Garmin activity summary,
-- stored as received so the check-in seed can be derived from them (and re-derived if the mapping changes).
-- Raw and insert-only like the other raw tables; one row per activity, never updated.
-- Nothing in feature engineering or scoring reads this table.
CREATE TABLE activity_effort (
    activity_id     BIGINT      PRIMARY KEY REFERENCES activity (id) ON DELETE CASCADE,
    workout_rpe     SMALLINT,   -- Garmin "workoutRpe", as received (0-100 in steps of 10)
    workout_feel    SMALLINT,   -- Garmin "workoutFeel", as received (25/50/75/100 on the coach's data)
    import_batch_id BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (workout_rpe IS NOT NULL OR workout_feel IS NOT NULL)
);
