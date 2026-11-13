-- Whole-day heart-rate zone minutes (feature set f3). One timeline per day: per-second
-- HR from recorded Garmin workouts, all-day monitoring HR everywhere else. Zones are
-- heart-rate-reserve bands (see app.metrics.hrr-zone-floors). An unrecorded workout then
-- counts at its real intensity instead of as low-weight background (ADR 0007).

ALTER TABLE activity_load
    ADD COLUMN hrr_zone_minutes NUMERIC(8, 2)[];   -- 6 values: below zone 1, zones 1..5

ALTER TABLE day_features
    ADD COLUMN zone_min_0 NUMERIC(7, 1),   -- below 50% HRR (rest, sleep, quiet daily life)
    ADD COLUMN zone_min_1 NUMERIC(7, 1),   -- 50-60%
    ADD COLUMN zone_min_2 NUMERIC(7, 1),   -- 60-70%
    ADD COLUMN zone_min_3 NUMERIC(7, 1),   -- 70-80%
    ADD COLUMN zone_min_4 NUMERIC(7, 1),   -- 80-90%
    ADD COLUMN zone_min_5 NUMERIC(7, 1),   -- 90%+
    -- Diagnostic only, never a model input: Whoop workouts in this cycle with no Garmin
    -- activity within 30 min (strain Whoop saw that our workout load cannot).
    ADD COLUMN diag_whoop_only_workouts INT NOT NULL DEFAULT 0;
