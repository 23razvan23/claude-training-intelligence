-- A Whoop export taken during the day contains the current, unfinished cycle (blank cycle end,
-- day strain still growing). The importer used to store it, and because raw rows are never
-- updated on re-import, the partial strain stayed forever (R-06). An unfinished record is not
-- a measurement: remove those rows so the next import inserts the finished cycle.
-- Day features built from them are derived data: the coach runs "Recompute features".

DELETE FROM whoop_journal_entry j
USING whoop_reference w
WHERE w.athlete_id = j.athlete_id
  AND w.cycle_start = j.cycle_start
  AND w.cycle_end IS NULL;

DELETE FROM whoop_reference WHERE cycle_end IS NULL;
