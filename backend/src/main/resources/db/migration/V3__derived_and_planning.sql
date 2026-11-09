-- Derived metrics and planning tables. Empty in Phase 0; the schema is laid
-- down now so later phases only add columns, not reshape the model.

-- Recomputable from raw data + model version; safe to drop per date range.
CREATE TABLE derived_day_metrics (
    athlete_id             BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    cycle_date             DATE        NOT NULL,
    model_version          TEXT        NOT NULL,
    recovery_pct           INT,
    recovery_band          TEXT        CHECK (recovery_band IN ('GREEN', 'YELLOW', 'RED')),
    strain                 NUMERIC(4, 1),
    sleep_performance_pct  INT,
    sleep_need_min         INT,
    loads                  JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- acute/chronic, running-only, TRIMP...
    contributors           JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- per-feature contributions for explanation
    data_quality           JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- stale / missing inputs
    computed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (athlete_id, cycle_date, model_version)
);

CREATE TABLE plan (
    id           BIGSERIAL PRIMARY KEY,
    athlete_id   BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    goal_type    TEXT        CHECK (goal_type IN ('GET_FIT', 'IMPROVE_DISTANCE', 'RACE')),
    goal         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    origin       TEXT        NOT NULL CHECK (origin IN ('GENERATED', 'MANUAL', 'IMPORTED')),
    status       TEXT        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'ARCHIVED')),
    start_date   DATE        NOT NULL,
    end_date     DATE        NOT NULL,
    config       JSONB       NOT NULL DEFAULT '{}'::jsonb,   -- engine rules snapshot used to generate it
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date)
);

CREATE TABLE plan_week (
    id                    BIGSERIAL PRIMARY KEY,
    plan_id               BIGINT  NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    week_index            INT     NOT NULL,
    start_date            DATE    NOT NULL,
    phase                 TEXT,   -- base, build, specific, taper, recovery
    target_distance_m     NUMERIC(10, 1),
    target_duration_min   INT,
    target_hard_sessions  INT,
    UNIQUE (plan_id, week_index)
);

CREATE TABLE session_template (
    id                   BIGSERIAL PRIMARY KEY,
    code                 TEXT    NOT NULL UNIQUE,
    name                 TEXT    NOT NULL,
    session_type         TEXT    NOT NULL,   -- easy, recovery, long, long_fast_finish, threshold, vo2max, race_pace, hills, strides, race, cross, rest
    structure            JSONB   NOT NULL DEFAULT '[]'::jsonb,
    est_duration_min     INT,
    est_strain           NUMERIC(4, 1),
    key_by_default       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE planned_session (
    id                    BIGSERIAL PRIMARY KEY,
    athlete_id            BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    plan_id               BIGINT      REFERENCES plan (id) ON DELETE CASCADE,
    plan_week_id          BIGINT      REFERENCES plan_week (id) ON DELETE SET NULL,
    template_id           BIGINT      REFERENCES session_template (id) ON DELETE SET NULL,
    session_date          DATE        NOT NULL,
    session_type          TEXT        NOT NULL,
    demand                TEXT        NOT NULL DEFAULT 'EASY' CHECK (demand IN ('KEY', 'SUPPORTING', 'EASY')),
    title                 TEXT        NOT NULL,
    structure             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    target_distance_m     NUMERIC(10, 1),
    target_duration_min   INT,
    est_strain            NUMERIC(4, 1),
    is_key                BOOLEAN     NOT NULL DEFAULT FALSE,
    is_taper              BOOLEAN     NOT NULL DEFAULT FALSE,
    status                TEXT        NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED', 'DONE', 'SKIPPED', 'REPLACED')),
    completed_activity_id BIGINT      REFERENCES activity (id) ON DELETE SET NULL,
    notes                 TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX planned_session_date ON planned_session (athlete_id, session_date);

CREATE TABLE adaptation_suggestion (
    id                  BIGSERIAL PRIMARY KEY,
    athlete_id          BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    planned_session_id  BIGINT      REFERENCES planned_session (id) ON DELETE CASCADE,
    for_date            DATE        NOT NULL,
    action              TEXT        NOT NULL CHECK (action IN ('KEEP', 'REDUCE', 'SWAP', 'REPLACE', 'MOVE', 'ADD')),
    before              JSONB,
    after               JSONB,
    reasons             JSONB       NOT NULL DEFAULT '[]'::jsonb,   -- 1-3 plain-language reasons with numbers
    inputs_snapshot     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    confidence          TEXT        CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    rules_version       TEXT        NOT NULL,
    model_version       TEXT,
    status              TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EDITED', 'EXPIRED')),
    coach_note          TEXT,
    decided_by          BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX adaptation_suggestion_pending ON adaptation_suggestion (athlete_id, for_date) WHERE status = 'PENDING';

ALTER TABLE subjective_log
    ADD CONSTRAINT subjective_log_planned_session_fk
    FOREIGN KEY (planned_session_id) REFERENCES planned_session (id) ON DELETE SET NULL;
