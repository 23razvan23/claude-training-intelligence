-- Stage 1b-1 (daily scoring), review item R-15.
--
-- A daily score depends on the model version AND on the day_features version it was
-- computed from, so derived_day_metrics records both. Rows are derived data (safe to
-- drop and recompute, V3): any row whose model version is unknown is removed rather
-- than guessed.
ALTER TABLE derived_day_metrics ADD COLUMN feature_version TEXT;

UPDATE derived_day_metrics d
SET feature_version = mv.feature_version
FROM model_version mv
WHERE mv.athlete_id = d.athlete_id AND mv.version = d.model_version;

DELETE FROM derived_day_metrics WHERE feature_version IS NULL;

ALTER TABLE derived_day_metrics ALTER COLUMN feature_version SET NOT NULL;

COMMENT ON COLUMN derived_day_metrics.model_version IS 'model_version.version of the model that produced this row';
COMMENT ON COLUMN derived_day_metrics.feature_version IS 'day_features.feature_version the scores were computed from';
COMMENT ON COLUMN derived_day_metrics.contributors IS 'recovery explanation: intercept and, per input, its value and points (ADR 0008 S6)';
COMMENT ON COLUMN derived_day_metrics.loads IS 'strain and sleep inputs used: workout/background/combined load or zone minutes, monitored minutes, yesterday''s strain, 7-day shortfall';
COMMENT ON COLUMN derived_day_metrics.data_quality IS 'blank reasons, low coverage, lower confidence, drift beyond training p01-p99, profile_changed, stale (ADR 0008, ADR 0012)';

-- R-15: V2's column comment predates V7, which re-dated Whoop cycles to the wake day.
COMMENT ON COLUMN whoop_reference.cycle_date IS 'local date of waking: the day this Whoop cycle belongs to (ADR 0002, V7); not the date of cycle start';
