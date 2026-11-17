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
                 "pairs": [[1000, 95.0], [2000, 97.0]], "label": "x"}"""));
        assertThat(paths).containsEntry("dailySleepDTO.averageRespirationValue", 12.0)
                .containsEntry("epochs[].respirationValue (mean)", 12.5)   // the -1 placeholder is left out
                .containsEntry("pairs[][1] (mean)", 96.0)
                .doesNotContainKey("label");
    }

    @Test
    void ranksTheFieldThatMatchesTheStoredValueOnMostNights() throws Exception {
        var answers = Map.of(
                "2026-11-15", JSON.readTree("{\"dailySleepDTO\": {\"averageRespirationValue\": 12.0}, \"averageRespiration\": 12.43}"),
                "2026-11-16", JSON.readTree("{\"dailySleepDTO\": {\"averageRespirationValue\": 12.0}, \"averageRespiration\": 11.9}"));
        var stored = Map.of(
                "2026-11-15", Map.of("avg_respiration", new BigDecimal("12.4")),
                "2026-11-16", Map.of("avg_respiration", new BigDecimal("11.9")));
        var result = SleepFieldSearch.search(answers, stored).stream().filter(r -> r.column().equals("avg_respiration")).findFirst()
                .orElseThrow();
        assertThat(result.nights()).isEqualTo(2);
        assertThat(result.candidates().getFirst()).isEqualTo(new SleepFieldSearch.Candidate("averageRespiration", 2));
        assertThat(result.candidates()).extracting(SleepFieldSearch.Candidate::path).doesNotContain("dailySleepDTO.averageRespirationValue");
    }
}
