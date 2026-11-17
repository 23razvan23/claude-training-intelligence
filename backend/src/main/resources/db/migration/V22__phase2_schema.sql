-- R-13 / ADR 0018: correct the Phase 2 tables before anything writes to them.
-- The tables changed here were empty on the coach's machine (checked 2026-11-17).
-- Nothing in day_features, derived_day_metrics, the raw tables or the views that
-- features are built from is touched (ADR 0008 frozen while the holdout runs).

-- 1. One source of truth for "key session": demand = 'KEY'.
ALTER TABLE planned_session DROP COLUMN is_key;

-- 2. Settings, versioned. A saved row is never changed: a new version is a new row.
CREATE TABLE settings_version (
    id          BIGSERIAL PRIMARY KEY,
    settings    JSONB       NOT NULL,
    note        TEXT,
    created_by  BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only guard. Direct UPDATE/DELETE is refused; changes made by a foreign key
-- cascade (deleting an athlete or a user) run one trigger level deeper and are allowed.
CREATE FUNCTION refuse_direct_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF pg_trigger_depth() > 1 THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    RAISE EXCEPTION '% is append-only: % refused (ADR 0018)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER settings_version_append_only
    BEFORE UPDATE OR DELETE ON settings_version
    FOR EACH ROW EXECUTE FUNCTION refuse_direct_change();

-- 3. Suggestions: the second session of a swap, which settings and signals produced
-- them, which engine run, and a key that stops a repeated run adding the same one again.
ALTER TABLE adaptation_suggestion
    ADD COLUMN counterpart_session_id BIGINT REFERENCES planned_session (id) ON DELETE SET NULL,
    ADD COLUMN settings_version       BIGINT REFERENCES settings_version (id),
    ADD COLUMN signals_version        TEXT,
    ADD COLUMN engine_run_date        DATE;

-- dedup_key (ADR 0018 D3): same session(s), date, action, rules version and settings
-- version = the same suggestion. Inputs are left out on purpose (coach, 2026-11-17).
-- Computed by the database so application code cannot get it wrong. The date enters
-- as a day number because a date-to-text cast depends on DateStyle.
ALTER TABLE adaptation_suggestion
    ADD COLUMN dedup_key TEXT GENERATED ALWAYS AS (md5(
        coalesce(planned_session_id::text, '-') || '|' ||
        coalesce(counterpart_session_id::text, '-') || '|' ||
        (for_date - DATE '2000-01-01')::text || '|' ||
        action || '|' ||
        rules_version || '|' ||
        coalesce(settings_version::text, '-'))) STORED;

CREATE UNIQUE INDEX adaptation_suggestion_dedup
    ON adaptation_suggestion (athlete_id, dedup_key) WHERE status = 'PENDING';

-- 4. Which suggestion changed a session, and which session a replacement replaces.
ALTER TABLE planned_session
    ADD COLUMN changed_by_suggestion_id BIGINT REFERENCES adaptation_suggestion (id) ON DELETE SET NULL,
    ADD COLUMN replaces_session_id      BIGINT REFERENCES planned_session (id) ON DELETE SET NULL;

-- Every change to a planned session, before and after. Append-only.
CREATE TABLE planned_session_revision (
    id                  BIGSERIAL PRIMARY KEY,
    athlete_id          BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    planned_session_id  BIGINT      NOT NULL REFERENCES planned_session (id) ON DELETE CASCADE,
    change_kind         TEXT        NOT NULL CHECK (change_kind IN ('CREATE', 'EDIT', 'DELETE')),
    before              JSONB,      -- NULL for CREATE
    after               JSONB,      -- NULL for DELETE
    suggestion_id       BIGINT      REFERENCES adaptation_suggestion (id) ON DELETE SET NULL,
    changed_by          BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    changed_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX planned_session_revision_session ON planned_session_revision (planned_session_id, changed_at);

CREATE TRIGGER planned_session_revision_append_only
    BEFORE UPDATE OR DELETE ON planned_session_revision
    FOR EACH ROW EXECUTE FUNCTION refuse_direct_change();

-- 5. Daily adaptive-engine signals (stage 2-2). Keyed by signals and settings version,
-- not by the Module 1 model. Not derived_day_metrics.loads, which stays the scorer's.
CREATE TABLE day_signals (
    athlete_id        BIGINT      NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    cycle_date        DATE        NOT NULL,
    signals_version   TEXT        NOT NULL,
    settings_version  BIGINT      NOT NULL REFERENCES settings_version (id),
    signals           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    computed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (athlete_id, cycle_date, signals_version, settings_version)
);

-- 6. Check-ins: an injury flag distinct from a niggle, and a way to say a niggle is gone.
ALTER TABLE subjective_log
    ADD COLUMN injury          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN niggle_resolved BOOLEAN NOT NULL DEFAULT FALSE;

-- "Latest wins" (ADR 0018 D6): per athlete, day and scope, the newest row counts.
-- Scope = the planned session; else the activity; else the whole day (coach, 2026-11-17).
CREATE VIEW subjective_current AS
SELECT DISTINCT ON (athlete_id, log_date, scope_key) *
FROM (
    SELECT s.*,
           coalesce('session:' || s.planned_session_id, 'activity:' || s.activity_id, 'day') AS scope_key
    FROM subjective_log s
) scoped
ORDER BY athlete_id, log_date, scope_key, created_at DESC, id DESC;
