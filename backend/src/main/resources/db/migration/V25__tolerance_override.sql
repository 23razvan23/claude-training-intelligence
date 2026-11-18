-- Stage 2-2 PR 2 (ADR 0021 D11): the coach's running-tolerance override, per athlete.
-- Append-only, like the other Phase 2 logs (ADR 0018 D2): a new value (or clearing it, km_per_week NULL) is a new
-- row; the newest row counts from its own day on, so older days keep the tolerance they had.
CREATE TABLE tolerance_override (
    id           BIGSERIAL PRIMARY KEY,
    athlete_id   BIGINT        NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    km_per_week  NUMERIC(6, 1) CHECK (km_per_week IS NULL OR km_per_week BETWEEN 0 AND 1000),
    note         TEXT,
    created_by   BIGINT        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX tolerance_override_athlete ON tolerance_override (athlete_id, created_at);

CREATE TRIGGER tolerance_override_append_only
    BEFORE UPDATE OR DELETE ON tolerance_override
    FOR EACH ROW EXECUTE FUNCTION refuse_direct_change();

-- R-15 TODO (PLAN backlog): the V2 comment ("local date of cycle_start") is stale since V7.
COMMENT ON COLUMN whoop_reference.cycle_date IS 'local date of the wake that ends the cycle (V7, ADR 0002)';
