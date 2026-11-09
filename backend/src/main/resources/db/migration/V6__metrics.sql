-- Phase 1 (Module 1) derived data. Everything here is recomputable from raw
-- tables + the athlete profile + feature_version, and safe to drop per range.

-- Heart-rate training load per activity.
CREATE TABLE activity_load (
    activity_id      BIGINT      PRIMARY KEY REFERENCES activity (id) ON DELETE CASCADE,
    athlete_id       BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    trimp_banister   NUMERIC(8, 2),
    trimp_edwards    NUMERIC(8, 2),
    hr_samples       INT,
    hr_coverage      NUMERIC(4, 3),          -- share of the activity's duration with usable HR
    method           TEXT        NOT NULL CHECK (method IN ('stream', 'avg_hr', 'none')),
    hr_max           INT,                    -- profile values used, for reproducibility
    hr_rest          INT,
    sex_assumed      BOOLEAN     NOT NULL DEFAULT FALSE,
    feature_version  TEXT        NOT NULL,
    computed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX activity_load_athlete ON activity_load (athlete_id);

-- One row per physiological-cycle day: the single feature source for both the
-- Python calibration job and (Phase 1b) the daily Java scoring. See ADR 0007.
CREATE TABLE day_features (
    athlete_id               BIGINT  NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    cycle_date               DATE    NOT NULL,
    feature_version          TEXT    NOT NULL,
    -- Garmin inputs
    rmssd                    NUMERIC(6, 1),
    ln_rmssd                 NUMERIC(6, 4),
    rhr                      INT,
    sleep_respiration        NUMERIC(4, 1),
    sleep_spo2               NUMERIC(4, 1),
    body_battery_high        INT,
    sleep_minutes            INT,
    deep_minutes             INT,
    rem_minutes              INT,
    light_minutes            INT,
    garmin_sleep_score       INT,
    -- training load (Garmin activities in this cycle)
    day_load_banister        NUMERIC(8, 2),
    day_load_edwards         NUMERIC(8, 2),
    run_km                   NUMERIC(6, 2),
    activity_count           INT     NOT NULL DEFAULT 0,
    load_missing_activities  INT     NOT NULL DEFAULT 0,  -- activities without a computable load
    -- personal baselines (trailing window, current day excluded)
    baseline_days            INT,
    ln_rmssd_z               NUMERIC(6, 3),
    rhr_dev                  NUMERIC(5, 2),
    resp_dev                 NUMERIC(5, 2),
    -- quality
    has_garmin_core          BOOLEAN NOT NULL,             -- HRV + resting HR + main sleep present
    -- Whoop reference (targets, never inputs)
    whoop_recovery           INT,
    whoop_strain             NUMERIC(4, 1),
    whoop_sleep_need_min     INT,
    whoop_sleep_perf         INT,
    whoop_asleep_min         INT,
    whoop_sleep_debt_min     INT,
    computed_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (athlete_id, cycle_date, feature_version)
);

-- Calibrated model versions written by the Python calibration job.
CREATE TABLE model_version (
    id               BIGSERIAL   PRIMARY KEY,
    athlete_id       BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    version          TEXT        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'CANDIDATE' CHECK (status IN ('CANDIDATE', 'ACTIVE', 'FROZEN', 'RETIRED')),
    feature_version  TEXT        NOT NULL,
    train_from       DATE,
    train_to         DATE,
    test_from        DATE,
    test_to          DATE,
    coefficients     JSONB       NOT NULL,   -- the contract the daily scoring reads (Phase 1b)
    metrics          JSONB       NOT NULL,
    passed           BOOLEAN     NOT NULL,
    report_html      TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (athlete_id, version)
);
