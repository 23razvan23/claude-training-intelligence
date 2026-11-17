package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The search for the field that holds the export's value (ADR 0015 D11), on synthetic answers. */
class SleepFieldSearchTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pathsIncludeNestedNumbersAndListMeans() throws Exception {
        var paths = SleepFieldSearch.numericPaths(JSON.readTree("""
                {"dailySleepDTO": {"averageRespirationValue": 12.0},
                 "epochs": [{"respirationValue": 12.0}, {"respirationValue": 13.0}, {"respirationValue": -1}],
                 "pairs": [[1, 95.0], [2, 97.0]], "label": "x"}"""));
        assertThat(paths).containsEntry("dailySleepDTO.averageRespirationValue", 12.0)
                .containsEntry("epochs[].respirationValue (mean)", 12.5)   // the -1 placeholder is left out
                .containsEntry("pairs[][1] (mean)", 96.0)
                .doesNotContainKey("label");
    }

    @Test
    void listMeansOverTheTimeAsleepOnly() throws Exception {
        // Night 23:00 → 07:00 GMT; the answer pads it with awake epochs before and after.
        var paths = SleepFieldSearch.numericPaths(JSON.readTree("""
                {"dailySleepDTO": {"sleepStartTimestampGMT": 1763247600000, "sleepEndTimestampGMT": 1763276400000},
                 "resp": [{"startTimeGMT": 1763244000000, "respirationValue": 16.0},
                          {"startTimeGMT": 1763250000000, "respirationValue": 12.0},
                          {"startTimeGMT": 1763260000000, "respirationValue": 12.8},
                          {"startTimeGMT": 1763280000000, "respirationValue": 17.0}],
                 "spo2": [{"epochTimestamp": "2025-11-16T00:00:00.0", "spo2Reading": 95},
                          {"epochTimestamp": "2025-11-16T08:30:00.0", "spo2Reading": 99}],
                 "hr": [[1763250000000, 50], [1763290000000, 70]]}"""));
        assertThat(paths.get("resp[].respirationValue (mean)")).isEqualTo(14.45);
        assertThat(paths.get("resp[].respirationValue (mean, asleep)")).isEqualTo(12.4);
        assertThat(paths).doesNotContainKey("resp[].startTimeGMT (mean)");
        assertThat(paths.get("spo2[].spo2Reading (mean, asleep)")).isEqualTo(95.0);
        assertThat(paths.get("hr[][1] (mean, asleep)")).isEqualTo(50.0);
        assertThat(paths.get("hr[][1] (mean)")).isEqualTo(60.0);
    }

    @Test
    void ranksByExactMatchesThenByClosenessAndShowsHowFarOff() throws Exception {
        var answers = Map.of(
                "2026-11-15", JSON.readTree("{\"dailySleepDTO\": {\"averageRespirationValue\": 12.0}, \"averageRespiration\": 12.43, \"near\": 12.5}"),
                "2026-11-16", JSON.readTree("{\"dailySleepDTO\": {\"averageRespirationValue\": 11.0}, \"averageRespiration\": 11.9, \"near\": 12.0}"));
        var stored = Map.of(
                "2026-11-15", Map.of("avg_respiration", new BigDecimal("12.4")),
                "2026-11-16", Map.of("avg_respiration", new BigDecimal("11.9")));
        var result = SleepFieldSearch.search(answers, stored).stream().filter(r -> r.column().equals("avg_respiration")).findFirst()
                .orElseThrow();
        assertThat(result.nights()).isEqualTo(2);
        assertThat(result.candidates().get(0).path()).isEqualTo("averageRespiration");
        assertThat(result.candidates().get(0).matches()).isEqualTo(2);
        assertThat(result.candidates().get(1).path()).isEqualTo("near");              // no exact match, but 0.10 off
        assertThat(result.candidates().get(1).meanAbsDiff()).isEqualByComparingTo("0.10");
        assertThat(result.candidates().get(2).path()).isEqualTo("dailySleepDTO.averageRespirationValue");
        assertThat(result.candidates().get(2).meanAbsDiff()).isEqualByComparingTo("0.65");
    }
}
