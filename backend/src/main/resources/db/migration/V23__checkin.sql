-- Stage 2-1b: athlete check-in (ADR 0020). subjective_log becomes truly append-only,
-- says where a row came from and whether the coach entered it, and refuses incoherent niggle answers.

-- 1. Where a row came from, and who it was entered for.
ALTER TABLE subjective_log
    ADD COLUMN source    TEXT    NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'GARMIN_SEED')),
    ADD COLUMN on_behalf BOOLEAN NOT NULL DEFAULT FALSE;   -- true: the coach entered it for the athlete

-- 2. A niggle answer needs a body location; "resolved" and a severity are different answers.
ALTER TABLE subjective_log
    ADD CONSTRAINT subjective_log_niggle_coherent CHECK (
        (niggle_severity IS NULL OR niggle_location IS NOT NULL)
        AND (NOT niggle_resolved OR (niggle_location IS NOT NULL AND niggle_severity IS NULL)));

-- 3. Append-only: a correction is a new row. Same guard as ADR 0018 (cascades one level deeper are allowed:
--    removing an athlete or user, or an activity link set to empty).
CREATE TRIGGER subjective_log_append_only
    BEFORE UPDATE OR DELETE ON subjective_log
    FOR EACH ROW EXECUTE FUNCTION refuse_direct_change();

-- 4. The view lists its columns, so the new ones appear in it ("latest wins" rule unchanged, ADR 0018 D6).
DROP VIEW subjective_current;
CREATE VIEW subjective_current AS
SELECT DISTINCT ON (athlete_id, log_date, scope_key) *
FROM (
    SELECT s.*,
           coalesce('session:' || s.planned_session_id, 'activity:' || s.activity_id, 'day') AS scope_key
    FROM subjective_log s
) scoped
ORDER BY athlete_id, log_date, scope_key, created_at DESC, id DESC;
