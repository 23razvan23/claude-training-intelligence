-- Stage 1b-4 fix (ADR 0015 D11, 2026-11-17): the first Garmin syncs stored the nightly breathing
-- rate, SpO2 and sleep stress from the live sleep summary, which gives them as whole numbers
-- (12.4 became 12.0), unlike the export. Those sleep rows are removed so they no longer count:
--   - a night also in an export counts with the export's version again (the newest remaining);
--   - a night only synced comes back with the next sync, translated correctly.
-- Only sleep rows written by a Garmin sync are touched. Activities, heart rate and daily summaries
-- from the same syncs matched the export exactly and stay. Raw data is otherwise append-only
-- (ADR 0003, 0010); like V12, this removes rows that should never have been stored.
-- After this migration the coach presses Recompute features once (README section 8).
DELETE FROM sleep_session
WHERE import_batch_id IN (SELECT id FROM import_batch WHERE kind = 'GARMIN_SYNC');
