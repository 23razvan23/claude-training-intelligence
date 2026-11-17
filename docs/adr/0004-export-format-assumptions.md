# ADR 0004: Export formats assumed without real files

- Status: **Garmin: verified against the coach's real export on 2026-11-09**; the list contents were verified with a second inspector run (see below). **Whoop: verified against the coach's real export on 2026-11-10** (see the Whoop section).
- Decided with the coach: no real export files were available in Phase 0, so the parsers follow the publicly documented layouts.

## How to verify
```bash
python3 -I tools/inspect_exports.py path/to/my_whoop_data.zip path/to/garmin_export.zip
```
The tool prints columns / JSON keys, value ranges and date coverage, but never free-text values. Compare its output with the tables below. Any difference is fixed in one place: `WhoopColumns.java` (header aliases) or `GarminFields.java` (file patterns, JSON paths and units).

## Whoop export (ZIP or loose CSVs, recognised by headers)
**Verified on the real export (2026-11-10):** 302 cycles from 2026-01-12 to 2026-11-08, with recovery 9–98 and strain 1.2–20.6. The headers matched. **`Cycle start time` is sleep onset, the evening before**, not waking; the cycle's day is the wake date (ADR 0002, migration V7).

Headers match case-insensitively, ignoring punctuation. Times are local, as `yyyy-MM-dd HH:mm:ss`, with `Cycle timezone` like `UTC+02:00`.

| File | Columns read |
|---|---|
| physiological_cycles.csv | Cycle start time, Cycle end time, Cycle timezone, Recovery score %, Resting heart rate (bpm), Heart rate variability (ms), Skin temp (celsius), Blood oxygen %, Day Strain, Energy burned (cal), Respiratory rate (rpm), Sleep performance %, Sleep need (min), Sleep debt (min), Asleep duration (min) |
| sleeps.csv | Cycle start/end/timezone, Sleep onset, Wake onset, Sleep performance %, Light sleep / Deep (SWS) / REM / Awake duration (min), Nap |
| workouts.csv | Cycle start/end/timezone, Workout start time, Workout end time, Duration (min), Activity name, Activity Strain, Energy burned (cal), Max HR (bpm), Average HR (bpm), Distance (meters), Altitude gain (meters) |
| journal_entries.csv | Cycle start/end/timezone, Question text, Answered yes, Notes |

## Garmin Connect account export (one ZIP)

### Verified on the real export (2026-11-09)
The export covered about 2024-09/11 → 2026-11 (the start month is not settled: PLAN §4 says 2024-09, this ADR said 2024-11; to be confirmed from the stored data), with 1,360 activities, ~720 nights and 21,451 FIT files.
- **Units:** activity `duration` is in ms (mean ≈ 37 min); `distance` and `elevationGain` are in cm (mean ≈ 4.4 km); `startTimeGmt` is epoch ms. All file patterns matched.
- **Resting HR:** `restingHeartRate` in UDSFile is smoothed (range 44–57). `currentDayRestingHeartRate` (41–63) is the day's own value. The importer reads `currentDayRestingHeartRate`, falling back to `restingHeartRate`.
- **Activity dates:** `startTimeLocal` holds local wall-clock time encoded as epoch ms. The activity's `calendar_date` is taken from it, so travel is handled correctly.
- **Overnight metrics:** sleep records carry `averageRespiration`, `avgSleepStress` and `spo2SleepSummary.averageSPO2/lowestSPO2`. These are stored on `sleep_session` (migration V4). 8 of 727 sleep records have no timestamps and are skipped.
- **HRV:** `healthStatusData` exists only from **2025-09-17**. Earlier days have no overnight HRV in these files. The 89 files in `DI-Connect-Metrics` are still to be checked for older HRV.
- **FIT files:** about 94% are non-activity (monitoring) files. The parser stops after `file_id` for those.
- **`calories`:** units are not verified (mean 1,364 per activity looks high for kcal). The value is stored as-is and is not used by any model.

### List contents (verified with the second inspector run)
- `allDayStress.aggregatorList[]`: `type` is TOTAL, AWAKE or ASLEEP; `averageStressLevel` uses −2 for "no reading". The importer reads TOTAL and drops negatives.
- `bodyBattery.bodyBatteryStatList[]`: `bodyBatteryStatType` includes HIGHEST and LOWEST (also SLEEPSTART, SLEEPEND and others); the value is in `statsValue`.
- `healthStatusData.metrics[]`: `type` is HRV, HR, RESPIRATION, SPO2 or SKIN_TEMP_C, each with `value`, `status` and baseline limits. Only HRV is imported, and values ≤ 0 are treated as placeholders.
- `averageSpo2Value` exists in UDSFile.
- Not imported yet, but available: skin temperature (`SKIN_TEMP_C`), naps (`sleepData.napList[]`) and per-activity `workoutRpe` / `workoutFeel` (143 activities). These are candidates for Phase 1 and 2.

### Still open
The file names in `DI-Connect-Metrics` (89 files), which might hold HRV from before 2025-09-17. Check with `inspect_exports.py --files`.

### Original assumptions
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
