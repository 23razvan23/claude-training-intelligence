package app.training.ingestion.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The live-to-export translation (ADR 0015 D5) on synthetic records: names, units, times, and keys already in export form. */
class GarminApiShapesTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void activityUnitsAndTimes() throws Exception {
        var a = GarminApiShapes.activity(JSON.readTree("""
                {"activityId": 42, "activityType": {"typeKey": "running"}, "startTimeGMT": "2026-11-17 05:30:00",
                 "startTimeLocal": "2026-11-17 07:30:00.0", "duration": 1800.5, "distance": 5000.0, "elevationGain": 12.3,
                 "averageHR": 150.0, "maxHR": 171.0}"""));
        assertThat(a.get("activityType").asText()).isEqualTo("running");
        assertThat(a.get("startTimeGmt").asLong()).isEqualTo(java.time.Instant.parse("2026-11-17T05:30:00Z").toEpochMilli());
        // Local wall clock written as if it were UTC, like the export.
        assertThat(a.get("startTimeLocal").asLong()).isEqualTo(java.time.Instant.parse("2026-11-17T07:30:00Z").toEpochMilli());
        assertThat(a.get("duration").asDouble()).isEqualTo(1_800_500.0);   // ms
        assertThat(a.get("distance").asDouble()).isEqualTo(500_000.0);     // cm
        assertThat(a.get("elevationGain").asDouble()).isEqualTo(1230.0);   // cm
        assertThat(a.get("avgHr").asInt()).isEqualTo(150);
    }

    @Test
    void dailySummaryUsesTheDaysOwnRestingHrAndBuildsTheExportLists() throws Exception {
        var o = GarminApiShapes.dailySummary(JSON.readTree("""
                {"calendarDate": "2026-11-17", "restingHeartRate": 46, "lastSevenDaysAvgRestingHeartRate": 48,
                 "averageStressLevel": 30, "bodyBatteryHighestValue": 91, "bodyBatteryLowestValue": 15, "averageSpo2": 95.0}"""));
        assertThat(o.get("currentDayRestingHeartRate").asInt()).isEqualTo(46);
        assertThat(o.get("restingHeartRate").asInt()).isEqualTo(48);
        assertThat(o.at("/allDayStress/aggregatorList/0/averageStressLevel").asInt()).isEqualTo(30);
        assertThat(o.at("/bodyBattery/bodyBatteryStatList/0/statsValue").asInt()).isEqualTo(91);
        assertThat(o.at("/bodyBattery/bodyBatteryStatList/1/bodyBatteryStatType").asText()).isEqualTo("LOWEST");
        assertThat(o.get("averageSpo2Value").asDouble()).isEqualTo(95.0);
    }

    @Test
    void exportShapedFieldsArePassedThroughUntouched() throws Exception {
        var o = GarminApiShapes.dailySummary(JSON.readTree("""
                {"calendarDate": "2026-11-17", "currentDayRestingHeartRate": 44, "restingHeartRate": 46,
                 "bodyBattery": {"bodyBatteryStatList": [{"bodyBatteryStatType": "HIGHEST", "statsValue": 70}]},
                 "bodyBatteryHighestValue": 99}"""));
        assertThat(o.get("currentDayRestingHeartRate").asInt()).isEqualTo(44);
        assertThat(o.at("/bodyBattery/bodyBatteryStatList/0/statsValue").asInt()).isEqualTo(70);
    }

    @Test
    void sleepAndHrv() throws Exception {
        var s = GarminApiShapes.sleep(JSON.readTree("""
                {"calendarDate": "2026-11-17", "sleepStartTimestampGMT": 1763330400000, "averageRespirationValue": 13.0,
                 "avgSpO2": 95.5, "lowestSpO2Value": 88.0}"""));
        assertThat(s.get("averageRespiration").asDouble()).isEqualTo(13.0);
        assertThat(s.at("/spo2SleepSummary/averageSPO2").asDouble()).isEqualTo(95.5);
        assertThat(s.at("/spo2SleepSummary/lowestSPO2").asDouble()).isEqualTo(88.0);
        assertThat(GarminExportImporter.gmt(s.get("sleepStartTimestampGMT").asText()))
                .isEqualTo(java.time.Instant.ofEpochMilli(1763330400000L));

        var h = GarminApiShapes.healthStatus(JSON.readTree("{\"calendarDate\": \"2026-11-17\", \"lastNightAvg\": 51, \"status\": \"BALANCED\"}"));
        assertThat(h.at("/metrics/0/type").asText()).isEqualTo("HRV");
        assertThat(h.at("/metrics/0/value").asInt()).isEqualTo(51);
        assertThat(h.at("/metrics/0/status").isMissingNode()).isTrue();
        assertThat(GarminApiShapes.healthStatus(JSON.readTree("{\"calendarDate\": \"2026-11-17\"}")).get("metrics")).isEmpty();
    }

    @Test
    void unreadableTimesAreLeftOut() {
        assertThat(GarminApiShapes.wallClockMillis(null)).isNull();
        assertThat(GarminApiShapes.wallClockMillis("yesterday")).isNull();
    }
}
