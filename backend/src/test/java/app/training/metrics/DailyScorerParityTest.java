package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Java/Python parity (ADR 0008, Consequences; R-03): for each committed fixture, the Java scorer
 * must reproduce every in-between value the reference scorer (calibration/parity.py) produced.
 * Unrounded values agree within 1e-6 absolute and 1e-7 relative; rounded values, bands, flags and
 * reasons agree exactly.
 */
public class DailyScorerParityTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final double ABS = 1e-6;
    static final double REL = 1e-7;

    @ParameterizedTest
    @ValueSource(strings = {"adr0008", "load", "zones"})
    void javaReproducesEveryValueOfTheReferenceScorer(String fixture) throws IOException {
        JsonNode expected = JSON.readTree(resource(fixture, "expected.json"));
        var contract = ScoringContract.parse(asContract(expected.get("coefficients")));
        List<DailyScorer.DayInput> rows = readFeatures(fixture);

        List<DailyScorer.DayScore> actual = DailyScorer.score(rows, contract);

        JsonNode days = expected.get("days");
        assertThat(actual).hasSize(days.size());
        for (int i = 0; i < days.size(); i++) {
            compare(fixture, days.get(i), actual.get(i));
        }
    }

    /**
     * The ADR 0008 worked example lists only the scoring blocks; give it the metadata a stored
     * contract carries so it passes the same strict parser as a real model version.
     */
    public static JsonNode asContract(JsonNode coefficients) {
        var c = (ObjectNode) coefficients.deepCopy();
        if (!c.has("schema_version")) {
            c.put("schema_version", 2);
            c.put("calibration_version", "adr0008-example");
            c.put("feature_version", "f3");
            var fc = c.putObject("feature_config");
            fc.putNull("hr_max");
            fc.putNull("hr_rest");
            fc.putNull("sex_assumed");
            var tr = c.putObject("training_ranges");
            tr.putObject("strain");
            tr.putObject("sleep");
            tr.putObject("recovery");
        }
        return c;
    }

    private static void compare(String fixture, JsonNode e, DailyScorer.DayScore a) {
        String at = fixture + " " + e.get("date").asText() + " ";
        assertThat(a.date()).as(at + "date").isEqualTo(LocalDate.parse(e.get("date").asText()));
        assertThat(a.hasRow()).as(at + "has_row").isEqualTo(e.get("has_row").asBoolean());
        close(at + "strain_raw", e.get("strain_raw"), a.strainRaw());
        close(at + "strain", e.get("strain"), a.strain());
        assertThat(a.strainReason()).as(at + "strain_reason").isEqualTo(text(e.get("strain_reason")));
        assertThat(a.lowCoverage()).as(at + "low_coverage").isEqualTo(e.get("low_coverage").asBoolean());
        close(at + "prev_strain", e.get("prev_strain"), a.prevStrain());
        close(at + "shortfall", e.get("shortfall"), a.shortfall());
        close(at + "sleep_need", e.get("sleep_need"), a.sleepNeed());
        close(at + "sleep_perf", e.get("sleep_perf"), a.sleepPerf());
        var ec = e.get("contributions");
        assertThat(a.contributions().keySet()).as(at + "contribution names")
                .containsExactlyElementsOf(iterable(ec.fieldNames()));
        ec.properties().forEach(f -> close(at + "contribution " + f.getKey(), f.getValue(), a.contributions().get(f.getKey())));
        close(at + "recovery_raw", e.get("recovery_raw"), a.recoveryRaw());
        close(at + "recovery", e.get("recovery"), a.recovery());
        var reasons = new ArrayList<DailyScorer.Reason>();
        e.get("recovery_reasons").forEach(r -> reasons.add(new DailyScorer.Reason(r.get("reason").asText(), text(r.get("detail")))));
        assertThat(a.recoveryReasons()).as(at + "recovery_reasons").containsExactlyElementsOf(reasons);
        assertThat(a.recoveryLowerConfidence()).as(at + "recovery_lower_confidence")
                .isEqualTo(e.get("recovery_lower_confidence").asBoolean());
        assertThat(a.band()).as(at + "band").isEqualTo(text(e.get("band")));
        var s = e.get("stored");
        assertThat(a.stored().strain()).as(at + "stored strain").isEqualTo(decimal(s.get("strain")));
        assertThat(a.stored().sleepNeed()).as(at + "stored sleep_need").isEqualTo(integer(s.get("sleep_need")));
        assertThat(a.stored().sleepPerf()).as(at + "stored sleep_perf").isEqualTo(integer(s.get("sleep_perf")));
        assertThat(a.stored().recovery()).as(at + "stored recovery").isEqualTo(integer(s.get("recovery")));
    }

    private static void close(String what, JsonNode expected, Double actual) {
        if (expected == null || expected.isNull()) {
            assertThat(actual).as(what + " should be blank").isNull();
            return;
        }
        if (actual == null) {
            fail(what + " is blank, expected " + expected.asDouble());
        }
        double e = expected.asDouble();
        double diff = Math.abs(actual - e);
        if (diff > ABS || diff > REL * Math.max(1.0, Math.abs(e))) {
            fail(what + ": expected " + e + ", got " + actual + " (difference " + diff + ")");
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Integer integer(JsonNode n) {
        return n == null || n.isNull() ? null : n.asInt();
    }

    /** One decimal, as NUMERIC(4, 1) stores it. */
    private static BigDecimal decimal(JsonNode n) {
        return n == null || n.isNull() ? null : new BigDecimal(n.asText()).setScale(1, java.math.RoundingMode.UNNECESSARY);
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        var list = new ArrayList<T>();
        it.forEachRemaining(list::add);
        return list;
    }

    /** day_features.csv: the input columns at database precision; an empty cell is NULL. */
    static List<DailyScorer.DayInput> readFeatures(String fixture) throws IOException {
        try (Reader r = new InputStreamReader(resource(fixture, "day_features.csv"), StandardCharsets.UTF_8)) {
            var rows = new ArrayList<DailyScorer.DayInput>();
            var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
            var parser = format.parse(r);
            for (CSVRecord rec : parser) {
                Map<String, Double> values = new HashMap<>();
                for (String col : parser.getHeaderNames()) {
                    if (col.equals("cycle_date") || col.equals("has_garmin_core")) continue;
                    String v = rec.get(col);
                    values.put(col, v.isEmpty() ? null : Double.valueOf(v));
                }
                rows.add(new DailyScorer.DayInput(
                        LocalDate.parse(rec.get("cycle_date")), Boolean.parseBoolean(rec.get("has_garmin_core")), values));
            }
            return rows;
        }
    }

    public static InputStream resource(String fixture, String file) {
        var in = DailyScorerParityTest.class.getResourceAsStream("/parity/" + fixture + "/" + file);
        assertThat(in).as("fixture " + fixture + "/" + file).isNotNull();
        return in;
    }
}
