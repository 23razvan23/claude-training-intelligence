-- R-10 / ADR 0010: a Garmin day fetched again (a later export; later the morning sync) replaces
-- the earlier, partial version in what the app reads. Raw rows stay append-only: a changed day
-- is stored as a revision row (source_id '<id>@<import batch>'), written by RawWriter only when
-- no stored version of that record has the same values, so re-importing any export inserts
-- nothing. Readers use the newest version. With one version per record (all data imported so
-- far), every view below returns exactly what it returned before.

-- Daily wellness: per record kind (daily summary 'uds', 'healthStatus', ...) the newest non-null
-- value of each column; then the kinds are combined as before (max; min for the low).
CREATE OR REPLACE VIEW daily_wellness_merged AS
WITH per_kind AS (
    SELECT athlete_id,
           source,
           calendar_date,
           (array_agg(resting_hr        ORDER BY ingested_at DESC, id DESC) FILTER (WHERE resting_hr IS NOT NULL))[1]        AS resting_hr,
           (array_agg(hrv_rmssd_ms      ORDER BY ingested_at DESC, id DESC) FILTER (WHERE hrv_rmssd_ms IS NOT NULL))[1]      AS hrv_rmssd_ms,
           (array_agg(hrv_status        ORDER BY ingested_at DESC, id DESC) FILTER (WHERE hrv_status IS NOT NULL))[1]        AS hrv_status,
           (array_agg(respiration_rate  ORDER BY ingested_at DESC, id DESC) FILTER (WHERE respiration_rate IS NOT NULL))[1]  AS respiration_rate,
           (array_agg(spo2_pct          ORDER BY ingested_at DESC, id DESC) FILTER (WHERE spo2_pct IS NOT NULL))[1]          AS spo2_pct,
           (array_agg(stress_avg        ORDER BY ingested_at DESC, id DESC) FILTER (WHERE stress_avg IS NOT NULL))[1]        AS stress_avg,
           (array_agg(body_battery_high ORDER BY ingested_at DESC, id DESC) FILTER (WHERE body_battery_high IS NOT NULL))[1] AS body_battery_high,
           (array_agg(body_battery_low  ORDER BY ingested_at DESC, id DESC) FILTER (WHERE body_battery_low IS NOT NULL))[1]  AS body_battery_low
    FROM daily_wellness
    GROUP BY athlete_id, source, calendar_date, split_part(source_id, '@', 1)
)
SELECT athlete_id,
       source,
       calendar_date,
       max(resting_hr)        AS resting_hr,
       max(hrv_rmssd_ms)      AS hrv_rmssd_ms,
       max(hrv_status)        AS hrv_status,
       max(respiration_rate)  AS respiration_rate,
       max(spo2_pct)          AS spo2_pct,
       max(stress_avg)        AS stress_avg,
       max(body_battery_high) AS body_battery_high,
       min(body_battery_low)  AS body_battery_low
FROM per_kind
GROUP BY athlete_id, source, calendar_date;

-- Sleep: a Garmin sleep record (source_id = Garmin's calendarDate) keeps only the rows of its
-- newest import. A re-processed night (other start time, or same start with other stages) then
-- replaces the earlier version instead of being added to it. Rows of one import share
-- ingested_at (each import is one transaction), so a night Garmin itself splits into two
-- records in one export is still read as before. Other sources are passed through unchanged.
CREATE INDEX sleep_session_record ON sleep_session (athlete_id, source, (split_part(source_id, '@', 1)), ingested_at);

CREATE VIEW sleep_session_current AS
SELECT s.*
FROM sleep_session s
WHERE s.source <> 'GARMIN'
   OR NOT EXISTS (
        SELECT 1 FROM sleep_session n
        WHERE n.athlete_id = s.athlete_id
          AND n.source = s.source
          AND split_part(n.source_id, '@', 1) = split_part(s.source_id, '@', 1)
          AND n.ingested_at > s.ingested_at);

-- Activities are placed in the cycle of the newest version of the night (definition otherwise as V2).
CREATE OR REPLACE VIEW activity_with_cycle AS
SELECT a.*,
       COALESCE(s.cycle_date, a.calendar_date) AS cycle_date,
       (s.cycle_date IS NULL)                  AS cycle_from_calendar
FROM activity a
LEFT JOIN LATERAL (
    SELECT ss.cycle_date
    FROM sleep_session_current ss
    WHERE ss.athlete_id = a.athlete_id
      AND NOT ss.is_nap
      AND ss.end_at <= a.start_at
      AND ss.end_at > a.start_at - INTERVAL '36 hours'
    ORDER BY ss.end_at DESC
    LIMIT 1
) s ON TRUE;
