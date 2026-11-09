-- Raw, append-only data. Imports dedup on (athlete, source, source_id, timestamp)
-- with ON CONFLICT DO NOTHING, so re-running an import changes nothing.
-- Rows here are never updated; derived data lives in V3 tables. See docs/adr/0003.

CREATE TABLE import_batch (
    id            BIGSERIAL PRIMARY KEY,
    athlete_id    BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    kind          TEXT        NOT NULL CHECK (kind IN ('WHOOP_EXPORT', 'GARMIN_EXPORT', 'GARMIN_SYNC', 'FIT_UPLOAD')),
    filename      TEXT,
    file_sha256   TEXT,
    status        TEXT        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    stats         JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- per-table inserted / duplicate / skipped counts
    warnings      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    error         TEXT,
    created_by    BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
CREATE INDEX import_batch_athlete ON import_batch (athlete_id, created_at DESC);

-- source: WHOOP | GARMIN
CREATE TABLE daily_wellness (
    id                  BIGSERIAL PRIMARY KEY,
    athlete_id          BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    source              TEXT        NOT NULL,
    source_id           TEXT        NOT NULL,
    calendar_date       DATE        NOT NULL,
    resting_hr          INT,
    hrv_rmssd_ms        NUMERIC(6, 1),   -- overnight average
    hrv_status          TEXT,
    respiration_rate    NUMERIC(4, 1),
    spo2_pct            NUMERIC(4, 1),
    stress_avg          INT,
    body_battery_high   INT,
    body_battery_low    INT,
    import_batch_id     BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    ingested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (athlete_id, source, source_id, calendar_date)
);

CREATE TABLE sleep_session (
    id                BIGSERIAL PRIMARY KEY,
    athlete_id        BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    source            TEXT        NOT NULL,
    source_id         TEXT        NOT NULL,
    start_at          TIMESTAMPTZ NOT NULL,
    end_at            TIMESTAMPTZ NOT NULL,
    cycle_date        DATE        NOT NULL,  -- local date of waking: the cycle this sleep opens (ADR 0002)
    is_nap            BOOLEAN     NOT NULL DEFAULT FALSE,
    deep_s            INT,
    light_s           INT,
    rem_s             INT,
    awake_s           INT,
    sleep_score       INT,
    import_batch_id   BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    ingested_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (athlete_id, source, source_id, start_at),
    CHECK (end_at > start_at)
);
CREATE INDEX sleep_session_end ON sleep_session (athlete_id, end_at);

CREATE TABLE activity (
    id                 BIGSERIAL PRIMARY KEY,
    athlete_id         BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    source             TEXT        NOT NULL,
    source_id          TEXT        NOT NULL,
    sport              TEXT        NOT NULL,   -- normalized: running, cycling, strength, hiking, walking, swimming, other
    source_sport       TEXT,                   -- as the source named it
    start_at           TIMESTAMPTZ NOT NULL,
    calendar_date      DATE        NOT NULL,   -- local date of start
    duration_s         INT,
    distance_m         NUMERIC(10, 1),
    elevation_gain_m   NUMERIC(7, 1),
    avg_hr             INT,
    max_hr             INT,
    calories           INT,
    training_load      NUMERIC(7, 1),          -- Garmin activity training load
    aerobic_te         NUMERIC(3, 1),
    anaerobic_te       NUMERIC(3, 1),
    whoop_strain       NUMERIC(4, 1),          -- Whoop workout strain (reference only)
    import_batch_id    BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    ingested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (athlete_id, source, source_id, start_at)
);
CREATE INDEX activity_start ON activity (athlete_id, start_at);

CREATE TABLE activity_stream (
    activity_id   BIGINT      NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    ts            TIMESTAMPTZ NOT NULL,
    hr            SMALLINT,
    speed_mps     REAL,
    cadence       SMALLINT,
    power         SMALLINT,
    altitude_m    REAL,
    distance_m    REAL,
    PRIMARY KEY (activity_id, ts)
);
SELECT create_hypertable('activity_stream', by_range('ts', INTERVAL '30 days'));

-- One row per Whoop physiological cycle (wake to wake). Ground truth for calibration.
CREATE TABLE whoop_reference (
    id                     BIGSERIAL PRIMARY KEY,
    athlete_id             BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    cycle_start            TIMESTAMPTZ NOT NULL,
    cycle_end              TIMESTAMPTZ,
    cycle_date             DATE        NOT NULL,   -- local date of cycle_start
    recovery_pct           INT,
    day_strain             NUMERIC(4, 1),
    hrv_rmssd_ms           NUMERIC(6, 1),
    resting_hr             INT,
    skin_temp_c            NUMERIC(4, 2),
    spo2_pct               NUMERIC(4, 1),
    respiration_rate       NUMERIC(4, 1),
    sleep_performance_pct  INT,
    sleep_need_min         INT,
    sleep_debt_min         INT,
    asleep_min             INT,
    energy_kcal            INT,
    import_batch_id        BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    ingested_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (athlete_id, cycle_start)
);
CREATE INDEX whoop_reference_date ON whoop_reference (athlete_id, cycle_date);

CREATE TABLE whoop_journal_entry (
    id               BIGSERIAL PRIMARY KEY,
    athlete_id       BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    cycle_start      TIMESTAMPTZ NOT NULL,
    cycle_date       DATE        NOT NULL,
    question         TEXT        NOT NULL,
    answered_yes     BOOLEAN,
    notes            TEXT,
    import_batch_id  BIGINT      REFERENCES import_batch (id) ON DELETE SET NULL,
    ingested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (athlete_id, cycle_start, question)
);

-- Athlete-entered check-ins. Append-only: a correction is a new row, the latest wins.
CREATE TABLE subjective_log (
    id                  BIGSERIAL PRIMARY KEY,
    athlete_id          BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    log_date            DATE        NOT NULL,
    activity_id         BIGINT      REFERENCES activity (id) ON DELETE SET NULL,
    planned_session_id  BIGINT,     -- FK added in V3
    rpe                 SMALLINT    CHECK (rpe BETWEEN 1 AND 10),
    soreness            SMALLINT    CHECK (soreness BETWEEN 1 AND 5),
    niggle_location     TEXT,
    niggle_severity     SMALLINT    CHECK (niggle_severity BETWEEN 1 AND 5),
    illness             BOOLEAN     NOT NULL DEFAULT FALSE,
    note                TEXT,
    created_by          BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX subjective_log_date ON subjective_log (athlete_id, log_date);

-- An activity belongs to the physiological cycle opened by the last main sleep that
-- ended before it started (within 36 h); otherwise to its local calendar date.
-- Computed in a view so raw rows stay immutable when sleep data arrives later.
CREATE VIEW activity_with_cycle AS
SELECT a.*,
       COALESCE(s.cycle_date, a.calendar_date) AS cycle_date,
       (s.cycle_date IS NULL)                  AS cycle_from_calendar
FROM activity a
LEFT JOIN LATERAL (
    SELECT ss.cycle_date
    FROM sleep_session ss
    WHERE ss.athlete_id = a.athlete_id
      AND NOT ss.is_nap
      AND ss.end_at <= a.start_at
      AND ss.end_at > a.start_at - INTERVAL '36 hours'
    ORDER BY ss.end_at DESC
    LIMIT 1
) s ON TRUE;

-- One row per athlete, source and day. Garmin wellness arrives as several raw
-- rows per day (daily summary, health status), so readers use this merged view.
CREATE VIEW daily_wellness_merged AS
SELECT athlete_id,
       source,
       calendar_date,
       max(resting_hr)        AS resting_hr,
       max(hrv_rmssd_ms)      AS hrv_rmssd_ms,
       max(hrv_status)        AS hrv_status,
       max(respiration_rate)  AS respiration_rate,
       max(spo2_pct)          AS spo2_pct,
       max(stress_avg)        AS stress_avg,
       max(body_battery_high) AS body_battery_high,
       min(body_battery_low)  AS body_battery_low
FROM daily_wellness
GROUP BY athlete_id, source, calendar_date;
