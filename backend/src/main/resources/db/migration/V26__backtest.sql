-- Stage 2-4 (ADR 0023): the backtest of the adaptive engine. Its results live in their own tables, under a run id,
-- and never in the live tables the coach uses (adaptation_suggestion, planned_session, day_signals).

-- One replay: which days, with which parameters, rules, signals, settings and model.
CREATE TABLE backtest_run (
    id                BIGSERIAL PRIMARY KEY,
    athlete_id        BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    from_date         DATE        NOT NULL,
    to_date           DATE        NOT NULL,
    params            JSONB       NOT NULL,          -- cut-off, races, hard weeks, how activities became the plan
    rules_version     TEXT        NOT NULL,
    signals_version   TEXT        NOT NULL,
    settings_version  BIGINT      NOT NULL REFERENCES settings_version (id),
    model_version     TEXT,                          -- the ACTIVE model whose stored scores were read (readiness)
    summary           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_by        BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (to_date >= from_date)
);
CREATE INDEX backtest_run_athlete ON backtest_run (athlete_id, created_at DESC);

-- One replayed morning: the signals the rules read that morning (today's and yesterday's, as stored in day_signals),
-- the health messages and the notes (what could not be checked, guardrail drops, late Garmin revisions).
CREATE TABLE backtest_day (
    run_id      BIGINT NOT NULL REFERENCES backtest_run (id) ON DELETE CASCADE,
    morning     DATE   NOT NULL,
    today       JSONB,
    yesterday   JSONB,
    health      JSONB  NOT NULL DEFAULT '[]'::jsonb,
    notes       JSONB  NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (run_id, morning)
);

-- What the engine would have suggested that morning. The "sessions" are Garmin activities (the plan stand-in),
-- so they point at activity rows, never at planned_session.
CREATE TABLE backtest_suggestion (
    id                       BIGSERIAL PRIMARY KEY,
    run_id                   BIGINT      NOT NULL REFERENCES backtest_run (id) ON DELETE CASCADE,
    morning                  DATE        NOT NULL,
    for_date                 DATE        NOT NULL,
    rule                     TEXT        NOT NULL,
    action                   TEXT        NOT NULL CHECK (action IN ('KEEP', 'REDUCE', 'SWAP', 'REPLACE', 'MOVE', 'ADD')),
    activity_id              BIGINT      REFERENCES activity (id) ON DELETE SET NULL,
    counterpart_activity_id  BIGINT      REFERENCES activity (id) ON DELETE SET NULL,
    before                   JSONB       NOT NULL,
    after                    JSONB       NOT NULL,
    reasons                  JSONB       NOT NULL,
    confidence               TEXT        NOT NULL CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    inputs_snapshot          JSONB       NOT NULL
);
CREATE INDEX backtest_suggestion_run ON backtest_suggestion (run_id, morning);

-- The coach's verdict on a sample of suggestions (training data for stage 4-3). Append-only: a change of mind is a
-- new row, and the newest row per suggestion counts.
CREATE TABLE backtest_mark (
    id             BIGSERIAL PRIMARY KEY,
    suggestion_id  BIGINT      NOT NULL REFERENCES backtest_suggestion (id) ON DELETE CASCADE,
    verdict        TEXT        NOT NULL CHECK (verdict IN ('SENSIBLE', 'NOT_SENSIBLE', 'UNSURE')),
    note           TEXT,
    created_by     BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX backtest_mark_suggestion ON backtest_mark (suggestion_id, created_at DESC);

CREATE TRIGGER backtest_mark_append_only
    BEFORE UPDATE OR DELETE ON backtest_mark
    FOR EACH ROW EXECUTE FUNCTION refuse_direct_change();
