package app.training.ingestion.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Turns Garmin Connect's live responses (what the sync sidecar fetches) into the record shapes
 * of a Garmin account export, so both go through the very same import code and land as the
 * same raw rows (ADR 0015 D5). This is the one place to fix if the live responses differ.
 *
 * <p>Each method starts from a copy of the live object, so a field that already has its export
 * name is used as is, and only adds the export names that are missing. Field names of the live
 * responses come from garminconnect 0.3.20's typed models; units from Garmin Connect.
 */
final class GarminApiShapes {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private GarminApiShapes() {}

    /** Daily summary (usersummary-service) → UDSFile record. */
    static ObjectNode dailySummary(JsonNode api) {
        ObjectNode o = api.deepCopy();
        // The day's own resting HR. In the export that is currentDayRestingHeartRate (restingHeartRate
        // there is smoothed, ADR 0004); the live summary calls the day's value restingHeartRate.
        // [assumption, ADR 0015 D5: checked on the coach's data with "Check against export"]
        if (!o.hasNonNull("currentDayRestingHeartRate") && api.hasNonNull("restingHeartRate")) {
            o.set("currentDayRestingHeartRate", api.get("restingHeartRate"));
        }
        if (api.hasNonNull("lastSevenDaysAvgRestingHeartRate")) o.set("restingHeartRate", api.get("lastSevenDaysAvgRestingHeartRate"));
        if (!o.has("respiration")) {
            ObjectNode r = F.objectNode();
            copy(api, "avgSleepRespirationValue", r, "avgSleepRespirationValue");
            copy(api, "avgWakingRespirationValue", r, "avgWakingRespirationValue");
            if (!r.isEmpty()) o.set("respiration", r);
        }
        if (!o.hasNonNull("averageSpo2Value")) copyFirst(api, o, "averageSpo2Value", "averageSpo2", "averageSpO2");
        if (!o.has("allDayStress") && api.hasNonNull("averageStressLevel")) {
            ObjectNode total = F.objectNode().put("type", "TOTAL");
            total.set("averageStressLevel", api.get("averageStressLevel"));
            o.set("allDayStress", F.objectNode().set("aggregatorList", F.arrayNode().add(total)));
        }
        if (!o.has("bodyBattery")) {
            var list = F.arrayNode();
            stat(api, "bodyBatteryHighestValue", "HIGHEST", list);
            stat(api, "bodyBatteryLowestValue", "LOWEST", list);
            if (!list.isEmpty()) o.set("bodyBattery", F.objectNode().set("bodyBatteryStatList", list));
        }
        return o;
    }

    /**
     * The live sleep answer → sleepData record. The nightly fields (times, stages, score) come from
     * its summary, dailySleepDTO; timestamps stay epoch ms, which the importer reads.
     *
     * <p>Bug found on the coach's data (2026-11-17, ADR 0015 D11): dailySleepDTO gives breathing
     * rate, SpO2 and sleep stress as whole numbers, while the export keeps decimals (12.4 vs 12.0).
     * So the precise values are taken from the rest of the answer, from fields with the export's
     * own names ({@link #PRECISE_RESPIRATION}, an object holding {@code averageSPO2}, like the
     * export's spo2SleepSummary). When none is there, the value is left out (the day then shows
     * the input as missing) rather than stored rounded, which would move recovery silently.
     * The whole-number values are never used.
     */
    static ObjectNode sleep(JsonNode live) {
        JsonNode dto = live.path("dailySleepDTO").isObject() ? live.get("dailySleepDTO") : live;
        ObjectNode o = dto.deepCopy();
        for (String rounded : ROUNDED_SLEEP_FIELDS) o.remove(rounded);
        if (!o.hasNonNull("averageRespiration")) {
            JsonNode r = findNumber(live, PRECISE_RESPIRATION, dto);
            if (r != null) o.set("averageRespiration", r);
        }
        if (!o.has("spo2SleepSummary")) {
            JsonNode spo2 = findObjectWith(live, "averageSPO2", dto);
            if (spo2 != null) {
                ObjectNode s = F.objectNode();
                copy(spo2, "averageSPO2", s, "averageSPO2");
                copy(spo2, "lowestSPO2", s, "lowestSPO2");
                o.set("spo2SleepSummary", s);
            } else if (dto.hasNonNull("lowestSpO2Value")) {
                // The lowest reading is a whole number on both sides, so the summary's is exact.
                o.set("spo2SleepSummary", F.objectNode().set("lowestSPO2", dto.get("lowestSpO2Value")));
            }
        }
        // avgSleepStress: no score reads it; kept only from a precise source elsewhere in the answer.
        JsonNode stress = findNumber(live, "avgSleepStress", dto);
        if (stress != null) o.set("avgSleepStress", stress);
        return o;
    }

    /** The export's name for the night's average breathing rate. */
    static final String PRECISE_RESPIRATION = "averageRespiration";

    /** Summary fields Garmin rounds to whole numbers (or computes otherwise); never stored. */
    static final java.util.List<String> ROUNDED_SLEEP_FIELDS = java.util.List.of(
            "averageRespirationValue", "averageSpO2Value", "avgSpO2", "avgSleepStress");

    /** The first numeric field {@code key} anywhere in {@code root}, outside {@code skip}. */
    static JsonNode findNumber(JsonNode root, String key, JsonNode skip) {
        if (root == null || root == skip) return null;
        if (root.isObject()) {
            JsonNode v = root.get(key);
            if (v != null && v.isNumber()) return v;
            for (var it = root.elements(); it.hasNext(); ) {
                JsonNode found = findNumber(it.next(), key, skip);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The first object anywhere in {@code root} (outside {@code skip}) with a numeric {@code key}. */
    static JsonNode findObjectWith(JsonNode root, String key, JsonNode skip) {
        if (root == null || root == skip) return null;
        if (root.isObject()) {
            if (root.path(key).isNumber()) return root;
            for (var it = root.elements(); it.hasNext(); ) {
                JsonNode found = findObjectWith(it.next(), key, skip);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * HRV summary (hrv-service hrvSummary) → healthStatusData record with one HRV metric. The live
     * HRV status uses another vocabulary (BALANCED, LOW, …) than the export's health status
     * (IN_RANGE, …), and no score reads it, so it is left empty rather than mixed.
     */
    static ObjectNode healthStatus(JsonNode hrv) {
        ObjectNode o = F.objectNode();
        copy(hrv, "calendarDate", o, "calendarDate");
        var metrics = F.arrayNode();
        if (hrv.hasNonNull("lastNightAvg")) {
            ObjectNode m = F.objectNode().put("type", GarminFields.HEALTH_HRV_TYPE);
            m.set("value", hrv.get("lastNightAvg"));
            metrics.add(m);
        }
        o.set("metrics", metrics);
        return o;
    }

    /**
     * Activity list entry (activitylist-service) → summarizedActivities record. The live list
     * gives times as "yyyy-MM-dd HH:mm:ss" text (GMT and local wall clock), duration in seconds
     * and distances in metres; the export gives epoch ms (local wall clock written as UTC),
     * milliseconds and centimetres (GarminFields).
     */
    static ObjectNode activity(JsonNode api) {
        ObjectNode o = F.objectNode();
        copy(api, "activityId", o, GarminFields.ACT_ID);
        JsonNode type = api.get("activityType");
        if (type != null && type.isObject() && type.hasNonNull("typeKey")) o.put("activityType", type.get("typeKey").asText());
        else if (type != null && type.isTextual()) o.put("activityType", type.asText());
        Long gmt = wallClockMillis(api.path("startTimeGMT").asText(null));
        Long local = wallClockMillis(api.path("startTimeLocal").asText(null));
        if (gmt != null) o.put(GarminFields.ACT_START_GMT_MS, gmt);
        if (local != null) o.put(GarminFields.ACT_START_LOCAL_MS, local);
        scaled(api, "duration", o, GarminFields.ACT_DURATION, 1000);
        scaled(api, "distance", o, GarminFields.ACT_DISTANCE, 100);
        scaled(api, "elevationGain", o, GarminFields.ACT_ELEVATION_GAIN, 100);
        copy(api, "averageHR", o, GarminFields.ACT_AVG_HR);
        copy(api, "maxHR", o, GarminFields.ACT_MAX_HR);
        copy(api, "calories", o, GarminFields.ACT_CALORIES);
        copy(api, "activityTrainingLoad", o, GarminFields.ACT_TRAINING_LOAD);
        copy(api, "aerobicTrainingEffect", o, GarminFields.ACT_AEROBIC_TE);
        copy(api, "anaerobicTrainingEffect", o, GarminFields.ACT_ANAEROBIC_TE);
        return o;
    }

    /** "2026-11-17 06:30:00" (optionally with fractions), read as UTC: epoch ms. Null if absent or unreadable. */
    static Long wallClockMillis(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text.strip().replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void copy(JsonNode from, String key, ObjectNode to, String as) {
        if (from.hasNonNull(key)) to.set(as, from.get(key));
    }

    private static void copyFirst(JsonNode from, ObjectNode to, String as, String... keys) {
        for (String k : keys) {
            if (from.hasNonNull(k)) {
                to.set(as, from.get(k));
                return;
            }
        }
    }

    private static void scaled(JsonNode from, String key, ObjectNode to, String as, double factor) {
        if (from.hasNonNull(key) && from.get(key).isNumber()) to.put(as, from.get(key).asDouble() * factor);
    }

    private static void stat(JsonNode api, String key, String type, com.fasterxml.jackson.databind.node.ArrayNode list) {
        if (api.hasNonNull(key)) {
            ObjectNode s = F.objectNode().put("bodyBatteryStatType", type);
            s.set("statsValue", api.get(key));
            list.add(s);
        }
    }
}
