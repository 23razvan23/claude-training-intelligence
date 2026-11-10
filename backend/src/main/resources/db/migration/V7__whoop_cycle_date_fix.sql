-- Whoop's export starts a physiological cycle at sleep onset (the evening before),
-- so dating a cycle by its start put every Whoop score one day early. Found on the
-- first real calibration: Whoop strain correlated 0.81 with the NEXT day's Garmin
-- load and -0.17 with the same day. A cycle belongs to the day you wake up (ADR 0002).
--
-- cycle_date is a derived label, not measured data, so it is corrected in place.
-- Rule, matching the importer: the wake date of the Whoop main sleep that starts
-- within 3 h of the cycle start; otherwise the local date of cycle start + 12 h.

UPDATE whoop_reference w
SET cycle_date = COALESCE(
        (SELECT s.cycle_date
         FROM sleep_session s
         WHERE s.athlete_id = w.athlete_id
           AND s.source = 'WHOOP'
           AND NOT s.is_nap
           AND s.start_at BETWEEN w.cycle_start - INTERVAL '3 hours' AND w.cycle_start + INTERVAL '3 hours'
         ORDER BY abs(extract(epoch FROM s.start_at - w.cycle_start))
         LIMIT 1),
        ((w.cycle_start AT TIME ZONE a.timezone) + INTERVAL '12 hours')::date)
FROM athlete a
WHERE a.id = w.athlete_id;

UPDATE whoop_journal_entry j
SET cycle_date = w.cycle_date
FROM whoop_reference w
WHERE w.athlete_id = j.athlete_id AND w.cycle_start = j.cycle_start;

-- Day features joined Whoop targets on the old dates: drop them so the next
-- "Recompute features" rebuilds them (derived data, always recomputable).
DELETE FROM day_features;

-- Model versions fitted on the shifted targets are not valid evidence.
UPDATE model_version SET status = 'RETIRED' WHERE status <> 'RETIRED';
