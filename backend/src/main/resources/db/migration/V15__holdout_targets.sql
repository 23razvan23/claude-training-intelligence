-- R-07 / ADR 0009: the pass marks and protocol values of the live comparison with Whoop,
-- frozen when a version is activated (MetricsRepository.activate copies them from the
-- version's own metrics.targets), so the holdout is never judged against edited targets.
ALTER TABLE model_version ADD COLUMN IF NOT EXISTS holdout_targets JSONB;

-- A version that was already ACTIVE before activation copied them (m15-20261115 on the
-- coach's machine) gets the marks it was calibrated against, with the ADR 0009 values agreed
-- on 2026-11-15: 90 compared recovery days, 95% Wilson intervals, window from the day after
-- its test window. Coach decision 2026-11-15. Only the new column is written: status and
-- activation times stay as they are. Rows already frozen are left alone, and a contract the
-- daily scoring can't read (no schema_version 2) is not frozen.
UPDATE model_version
SET holdout_targets = (metrics -> 'targets') || jsonb_build_object(
        'strain_min_monitored_minutes', coefficients -> 'strain' -> 'min_monitored_minutes',
        'min_recovery_days', 90,
        'confidence', 0.95,
        'interval', 'wilson',
        'window_from', to_char(test_to + 1, 'YYYY-MM-DD'),
        'protocol', 'ADR 0009',
        'source', 'V15')
WHERE status = 'ACTIVE'
  AND holdout_targets IS NULL
  AND jsonb_typeof(metrics -> 'targets') = 'object'
  AND test_to IS NOT NULL
  AND coefficients -> 'schema_version' = '2'::jsonb;
