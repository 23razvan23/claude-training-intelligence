package app.training.ingestion.garmin;

import java.util.List;
import java.util.regex.Pattern;

/**
 * File patterns, JSON field names and units the Garmin bulk-export importer relies on.
 * THIS IS THE ONE PLACE TO FIX if a real export differs.
 * Assumed layout: docs/adr/0004-export-format-assumptions.md.
 */
final class GarminFields {
    private GarminFields() {}

    // --- which entries in the export ZIP we read (matched against the full entry path) ---
    static final Pattern SUMMARIZED_ACTIVITIES = Pattern.compile("(?i).*summarizedActivities.*\\.json$");
    static final Pattern SLEEP = Pattern.compile("(?i).*sleepData.*\\.json$");
    static final Pattern DAILY_SUMMARY = Pattern.compile("(?i).*UDSFile.*\\.json$");
    static final Pattern HEALTH_STATUS = Pattern.compile("(?i).*(healthStatusData|hrvStatus).*\\.json$");
    static final Pattern FIT = Pattern.compile("(?i).*\\.fit$");
    static final Pattern NESTED_ZIP = Pattern.compile("(?i).*\\.zip$");

    // --- summarizedActivities.json: [{"summarizedActivitiesExport": [ {...}, ... ]}] ---
    static final String ACTIVITIES_WRAPPER = "summarizedActivitiesExport";
    static final String ACT_ID = "activityId";
    static final List<String> ACT_TYPE = List.of("activityType", "sportType");
    static final String ACT_START_GMT_MS = "startTimeGmt";        // epoch milliseconds
    static final String ACT_DURATION = "duration";                // milliseconds
    static final double ACT_DURATION_TO_SECONDS = 1 / 1000.0;
    static final String ACT_DISTANCE = "distance";                // centimetres
    static final double ACT_DISTANCE_TO_METRES = 1 / 100.0;
    static final String ACT_ELEVATION_GAIN = "elevationGain";     // centimetres
    static final double ACT_ELEVATION_TO_METRES = 1 / 100.0;
    static final String ACT_AVG_HR = "avgHr";
    static final String ACT_MAX_HR = "maxHr";
    static final String ACT_CALORIES = "calories";
    static final String ACT_TRAINING_LOAD = "activityTrainingLoad";
    static final String ACT_AEROBIC_TE = "aerobicTrainingEffect";
    static final String ACT_ANAEROBIC_TE = "anaerobicTrainingEffect";

    // --- sleepData.json: [ {...per night...} ] ---
    static final String SLEEP_DATE = "calendarDate";
    static final String SLEEP_START_GMT = "sleepStartTimestampGMT"; // "2024-01-14T22:31:00.0", UTC, no zone suffix
    static final String SLEEP_END_GMT = "sleepEndTimestampGMT";
    static final String SLEEP_DEEP_S = "deepSleepSeconds";
    static final String SLEEP_LIGHT_S = "lightSleepSeconds";
    static final String SLEEP_REM_S = "remSleepSeconds";
    static final String SLEEP_AWAKE_S = "awakeSleepSeconds";
    static final List<String> SLEEP_SCORE_PATHS = List.of("sleepScores/overallScore", "sleepScores/overall/value", "overallSleepScore/value");

    // --- UDSFile_*.json: [ {...per calendar day...} ] ---
    static final String DAY_DATE = "calendarDate";                 // "2024-01-15" or {"date": "2024-01-15"}
    static final String DAY_RESTING_HR = "restingHeartRate";
    static final List<String> DAY_RESPIRATION = List.of("respiration/avgSleepRespirationValue", "respiration/avgWakingRespirationValue", "avgWakingRespirationValue");
    static final List<String> DAY_SPO2 = List.of("averageSpo2Value", "averageSpo2", "spo2/averageSPO2");
    static final String DAY_STRESS_LIST = "allDayStress/aggregatorList";  // [{"type":"TOTAL","averageStressLevel":31}]
    static final String DAY_BODY_BATTERY_LIST = "bodyBattery/bodyBatteryStatList"; // [{"bodyBatteryStatType":"HIGHEST","statsValue":88}]

    // --- healthStatusData.json: [{"calendarDate": "...", "metrics": [{"type":"HRV","value":52.0,"status":"IN_RANGE"}]}] ---
    static final String HEALTH_DATE = "calendarDate";
    static final String HEALTH_METRICS = "metrics";
    static final String HEALTH_HRV_TYPE = "HRV";
}
