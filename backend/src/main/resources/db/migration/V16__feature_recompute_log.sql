-- Stage 1b-3 / ADR 0009 H8, ADR 0013 D6: a log of every "Recompute features" run, so the live
-- comparison with Whoop can mark each run inside its window (day_features.computed_at only
-- remembers the latest one). Written by the app; nothing else reads or changes it.
CREATE TABLE feature_recompute_log (
    id                   BIGSERIAL   PRIMARY KEY,
    athlete_id           BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    ran_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    ran_by               BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    feature_version      TEXT        NOT NULL,
    feature_days         INT         NOT NULL,
    scored_model_version TEXT,       -- null when no score was written (no usable ACTIVE model)
    scored_days          INT         NOT NULL DEFAULT 0
);
CREATE INDEX feature_recompute_log_athlete ON feature_recompute_log (athlete_id, ran_at);
