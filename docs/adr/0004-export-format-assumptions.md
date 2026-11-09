# ADR 0004: Export formats assumed without real files

- Status: **provisional — verify against the real exports before Phase 1**
- Decided with the coach: no real export files were available in Phase 0, so the parsers follow the publicly documented layouts.

## How to verify
```bash
python3 -I tools/inspect_exports.py path/to/my_whoop_data.zip path/to/garmin_export.zip
```
The tool prints columns / JSON keys, value ranges and date coverage, but never free-text values. Compare its output with the tables below. Any difference is fixed in one place: `WhoopColumns.java` (header aliases) or `GarminFields.java` (file patterns, JSON paths and units).

## Whoop export (ZIP or loose CSVs, recognised by headers)
Headers match case-insensitively, ignoring punctuation. Times are local, as `yyyy-MM-dd HH:mm:ss`, with `Cycle timezone` like `UTC+02:00`.

| File | Columns read |
|---|---|
| physiological_cycles.csv | Cycle start time, Cycle end time, Cycle timezone, Recovery score %, Resting heart rate (bpm), Heart rate variability (ms), Skin temp (celsius), Blood oxygen %, Day Strain, Energy burned (cal), Respiratory rate (rpm), Sleep performance %, Sleep need (min), Sleep debt (min), Asleep duration (min) |
| sleeps.csv | Cycle start/end/timezone, Sleep onset, Wake onset, Sleep performance %, Light sleep / Deep (SWS) / REM / Awake duration (min), Nap |
| workouts.csv | Cycle start/end/timezone, Workout start time, Workout end time, Duration (min), Activity name, Activity Strain, Energy burned (cal), Max HR (bpm), Average HR (bpm), Distance (meters), Altitude gain (meters) |
| journal_entries.csv | Cycle start/end/timezone, Question text, Answered yes, Notes |

## Garmin Connect account export (one ZIP)
| Entry (path pattern) | Fields read | Units assumed |
|---|---|---|
| `*summarizedActivities*.json`: `[{"summarizedActivitiesExport":[…]}]` | activityId, activityType/sportType, startTimeGmt, duration, distance, elevationGain, avgHr, maxHr, calories, activityTrainingLoad, aerobicTrainingEffect, anaerobicTrainingEffect | startTimeGmt epoch **ms**; duration **ms**; distance and elevationGain **cm** |
| `*sleepData*.json` | calendarDate, sleepStartTimestampGMT, sleepEndTimestampGMT, deep/light/rem/awakeSleepSeconds, sleepScores.overallScore (or .overall.value) | timestamps are UTC ISO local date-times |
| `*UDSFile*.json` (daily summary) | calendarDate, restingHeartRate, allDayStress.aggregatorList[type=TOTAL].averageStressLevel, bodyBattery.bodyBatteryStatList[HIGHEST/LOWEST].statsValue, respiration.avgSleepRespirationValue, averageSpo2Value | negative stress = no reading |
| `*healthStatusData*.json` | calendarDate, metrics[type=HRV].value / status | overnight average RMSSD, ms |
| `*.fit` (top level or inside nested `UploadedFiles_*.zip`) | file_id, session, record messages (Garmin FIT SDK) | SI units |

## Safeguards already in place
- Unrecognised CSVs and ZIPs are reported as batch warnings, not silently ignored.
- A running activity faster than 12 m/s raises a warning, catching a wrong distance/duration unit.
- Rows that fail to parse are counted as `skipped`, each with a line-numbered warning.

## Known uncertainty
- **Overnight HRV in the Garmin export** is the least certain mapping. If the export carries no `healthStatusData`, Garmin coverage will show 0 HRV days, and Phase 1 needs another source: the sync API, or HRV from FIT `hrv` messages.
