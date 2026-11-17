package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.training.IntegrationTest;
import app.training.ingestion.Fixtures;
import app.training.ingestion.ImportBatchRepository;
import app.training.ingestion.ImportKind;
import app.training.ingestion.ImportRunner;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The golden-file check (R-14, ADR 0017) on synthetic data only: the parity fixture (R-03) for the scoring part, and
 * synthetic Whoop and Garmin exports for the full replay from raw data.
 */
class GoldenIT extends IntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate FIRST = LocalDate.of(2026, 2, 1);
    static final int DAYS = 40;
    static final LocalDate FROM = FIRST.plusDays(5);
    static final LocalDate TO = FIRST.plusDays(34);

    @Autowired GoldenService golden;
    @Autowired GoldenProperties properties;
    @Autowired FeatureRecomputeService recompute;
    @Autowired ScoringService scoring;
    @Autowired ImportRunner runner;
    @Autowired ImportBatchRepository batches;
    @Autowired UserRepository users;
    @TempDir Path dir;
    long coach;
    JsonNode coefficients;
    final List<Path> written = new ArrayList<>();
    String originalWellnessView;

    @BeforeEach
    void setUp() throws Exception {
        coach = users.insert("coach-g" + System.nanoTime(), "x", Role.COACH, null);
        coefficients = JSON.readTree(DailyScorerParityTest.resource("load", "expected.json")).get("coefficients");
        originalWellnessView = null;
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (originalWellnessView != null) {
            jdbc.sql("CREATE OR REPLACE VIEW daily_wellness_merged AS " + originalWellnessView).update();
        }
        for (Path p : written) Files.deleteIfExists(p);
    }

    // ------------------------------------------------------------------ helpers

    private long athlete() {
        return jdbc.sql("INSERT INTO athlete (name, sex, hr_max, resting_hr) VALUES ('G', 'M', 190, 48) RETURNING id")
                .query(Long.class).single();
    }

    /** An athlete with synthetic imports (40 days), recomputed features and scores from an ACTIVE model. */
    private long athleteWithImports() throws Exception {
        long a = athlete();
        Fixtures.whoopCsvs(dir, FIRST, DAYS);
        Path zip = Fixtures.garminExport(dir, FIRST, DAYS);
        run(a, ImportKind.WHOOP_EXPORT, dir.resolve("physiological_cycles.csv"));
        run(a, ImportKind.GARMIN_EXPORT, zip);
        activeModel(a, coefficients);
        recompute.recompute(a, null, FeatureRecomputeService.SOURCE_MANUAL);
        return a;
    }

    /** An athlete holding only the parity fixture's day_features (no raw data): for the scoring-only mode. */
    private long athleteWithFixtureFeatures() throws Exception {
        long a = athlete();
        for (var row : DailyScorerParityTest.readFeatures("load")) {
            var cols = new ArrayList<String>(ScoringRepository.INPUT_COLUMNS);
            var sql = "INSERT INTO day_features (athlete_id, cycle_date, feature_version, has_garmin_core, " + String.join(", ", cols)
                    + ") VALUES (?, ?, 'f3', ?" + ", ?".repeat(cols.size()) + ")";
            var params = new ArrayList<Object>(List.of(a, row.date(), row.hasGarminCore()));
            cols.forEach(c -> params.add(row.values().get(c)));
            jdbc.sql(sql).params(params).update();
        }
        activeModel(a, coefficients);
        return a;
    }

    private void run(long athlete, ImportKind kind, Path file) throws Exception {
        Path copy = Files.createTempFile(dir, "u", file.getFileName().toString());
        Files.copy(file, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        long id = batches.create(athlete, kind, "t", null, coach);
        runner.run(id, athlete, kind, List.of(copy), null);
        assertThat(batches.find(id).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    private void activeModel(long athlete, JsonNode coefficients) {
        jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, 'm-golden', 'ACTIVE', 'f3', ?::jsonb, '{}', true, '')""")
                .params(athlete, coefficients.toString()).update();
    }

    private GoldenService.FreezeResult freeze(long a) {
        var r = golden.freeze(a, FROM, TO, false);
        written.add(r.file());
        return r;
    }

    /** Every stored row the check must not touch, as a stable text digest. */
    private String storedState(long a) {
        var sb = new StringBuilder();
        for (String t : List.of("day_features", "derived_day_metrics", "activity_load", "daily_wellness", "sleep_session", "activity",
                "whoop_reference", "feature_recompute_log")) {
            sb.append(t).append(':').append(count("SELECT count(*) FROM " + t + " WHERE athlete_id = ?", a)).append(':')
                    .append(jdbc.sql("SELECT coalesce(md5(string_agg(x::text, '|' ORDER BY x::text)), '') FROM " + t + " x WHERE athlete_id = ?")
                            .param(a).query(String.class).single())
                    .append('\n');
        }
        return sb.toString();
    }

    private static String all(GoldenService.CheckResult r) {
        return String.join("\n", r.lines());
    }

    /** A line of a check report that names this date. */
    private static String lineFor(GoldenService.CheckResult r, LocalDate d) {
        return r.lines().stream().filter(l -> l.trim().startsWith(d.toString())).findFirst().orElse("");
    }

    // ---------------------------------------------------- full replay from raw data

    @Test
    void freezeThenCheckIsCleanAndChangesNothingStored() throws Exception {
        long a = athleteWithImports();
        var frozen = freeze(a);
        assertThat(frozen.days()).isEqualTo(30);
        assertThat(frozen.file()).hasParent(properties.dir()).exists();
        String before = storedState(a);

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isZero();
        assertThat(r.lines()).contains("0 differences");
        assertThat(storedState(a)).as("a check must not change anything stored").isEqualTo(before);
        String text = Files.readString(frozen.file());
        assertThat(text).contains("\"featureColumns\"").contains("\"salt\"");
        // Hashes only: the stored resting HR of the first frozen day does not appear as a value.
        assertThat(text).doesNotContain("\"rhr\" : 47");
    }

    @Test
    void aNewerImportAfterTheFreezeIsNotACodeChange() throws Exception {
        long a = athleteWithImports();
        freeze(a);
        // A later Garmin revision of a day inside the window (ADR 0010), in a newer import batch.
        long batch = batches.create(a, ImportKind.GARMIN_EXPORT, "later", null, coach);
        jdbc.sql("UPDATE import_batch SET status = 'SUCCEEDED' WHERE id = ?").param(batch).update();
        LocalDate day = FROM.plusDays(10);
        int before = jdbc.sql("SELECT rhr FROM day_features WHERE athlete_id = ? AND cycle_date = ?").params(a, day).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO daily_wellness (athlete_id, source, source_id, calendar_date, resting_hr, import_batch_id)
                VALUES (?, 'GARMIN', ?, ?, 71, ?)""").params(a, "uds@" + batch, day, batch).update();
        recompute.recompute(a, null, FeatureRecomputeService.SOURCE_MANUAL);
        int after = jdbc.sql("SELECT rhr FROM day_features WHERE athlete_id = ? AND cycle_date = ?").params(a, day).query(Integer.class).single();
        assertThat(after).as("the new revision is what the app now shows").isNotEqualTo(before);

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isZero();
        assertThat(r.lines()).contains("0 differences");
    }

    @Test
    void aCodeChangeIsReportedWithDatesAndColumnsAndNoValues() throws Exception {
        long a = athleteWithImports();
        freeze(a);
        // Stand-in for a code change in the feature pipeline: the view the features read now gives another resting HR.
        originalWellnessView = jdbc.sql("SELECT pg_get_viewdef('daily_wellness_merged'::regclass)").query(String.class).single()
                .replaceAll(";\\s*$", "");
        jdbc.sql("""
                CREATE OR REPLACE VIEW daily_wellness_merged AS
                SELECT athlete_id, source, calendar_date, resting_hr + 3 AS resting_hr, hrv_rmssd_ms, hrv_status, respiration_rate, spo2_pct,
                       stress_avg, body_battery_high, body_battery_low
                FROM (""" + originalWellnessView + ") o").update();

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isEqualTo(1);
        assertThat(all(r)).contains("CODE CHANGED");
        // Exactly one column on every day. The change shifts the resting HR and its baseline alike, so the deviation and the scores
        // stay the same, and the check says so: it names only what moved.
        assertThat(all(r)).contains("30 differences in 30 days");
        assertThat(lineFor(r, FROM)).isEqualTo("  " + FROM + "  day_features: rhr");
        assertThat(lineFor(r, FROM.plusDays(11))).isEqualTo("  " + FROM.plusDays(11) + "  day_features: rhr");
        assertThat(all(r)).doesNotContain("derived_day_metrics").contains("columns involved in all differing days: day_features.rhr");
        // Dates and names only: no measured value appears next to the column names.
        assertThat(all(r)).doesNotContainPattern("rhr\\S*\\s*[=:]\\s*\\d");
    }

    @Test
    void aChangedProfileIsInputsChangedNotCodeChanged() throws Exception {
        long a = athleteWithImports();
        freeze(a);
        jdbc.sql("UPDATE athlete SET hr_max = 185 WHERE id = ?").param(a).update();

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isEqualTo(3);
        assertThat(all(r)).contains("INPUTS CHANGED").contains("profile").contains("hr_max").doesNotContain("CODE CHANGED");
    }

    @Test
    void rawRowsRemovedSinceTheFreezeAreInputsChanged() throws Exception {
        long a = athleteWithImports();
        freeze(a);
        jdbc.sql("DELETE FROM sleep_session WHERE athlete_id = ? AND cycle_date = ?").params(a, FROM.plusDays(3)).update();

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isEqualTo(3);
        assertThat(all(r)).contains("INPUTS CHANGED").contains("sleep_session");
    }

    @Test
    void freezeRefusesWhenTheStoredRowsAreNotWhatTheCodeProducesNow() throws Exception {
        long a = athleteWithImports();
        LocalDate day = FROM.plusDays(7);
        jdbc.sql("UPDATE day_features SET rhr = rhr + 5 WHERE athlete_id = ? AND cycle_date = ?").params(a, day).update();

        assertThatThrownBy(() -> golden.freeze(a, FROM, TO, false))
                .isInstanceOf(GoldenService.Refused.class)
                .hasMessageContaining("Recompute features")
                .hasMessageContaining(day.toString())
                .hasMessageContaining("rhr");
        assertThat(count("SELECT count(*) FROM day_features WHERE athlete_id = ? AND cycle_date = ? AND rhr IS NOT NULL", a, day)).isEqualTo(1);
    }

    @Test
    void freezeAndCheckRefuseWhileAnImportIsRunning() throws Exception {
        long a = athleteWithImports();
        freeze(a);
        batches.create(a, ImportKind.GARMIN_EXPORT, "pending", null, coach);
        assertThatThrownBy(() -> golden.freeze(a, FROM, TO, false)).isInstanceOf(GoldenService.Refused.class).hasMessageContaining("import");
        assertThatThrownBy(() -> golden.check(a, null)).isInstanceOf(GoldenService.Refused.class).hasMessageContaining("import");
    }

    @Test
    void theDefaultWindowIsSixtyDaysEndingTwoDaysBeforeTheLastDay() throws Exception {
        long a = athleteWithImports();
        var r = golden.freeze(a, null, null, false);
        written.add(r.file());
        LocalDate last = FIRST.plusDays(DAYS - 1);
        assertThat(r.to()).isEqualTo(last.minusDays(2));
        assertThat(r.from()).isEqualTo(last.minusDays(2 + 59));
        assertThat(r.days()).isEqualTo(DAYS - 2); // only 38 days exist before the end
    }

    @Test
    void checkWithoutAFileSaysWhatToDo() throws Exception {
        long a = athleteWithImports();
        assertThatThrownBy(() -> golden.check(a, null)).isInstanceOf(GoldenService.Refused.class).hasMessageContaining("golden freeze");
    }

    // ------------------------------------------- scoring only, on the parity fixture

    static final LocalDate FIXTURE_FROM = LocalDate.of(2026, 9, 1);
    static final LocalDate FIXTURE_TO = LocalDate.of(2026, 10, 27);

    private long frozenFixture() throws Exception {
        long a = athleteWithFixtureFeatures();
        scoring.recompute(a, null, null);
        var r = golden.freeze(a, FIXTURE_FROM, FIXTURE_TO, true);
        written.add(r.file());
        assertThat(r.days()).isEqualTo(57);
        return a;
    }

    private ObjectNode readFile(long a) throws Exception {
        return (ObjectNode) JSON.readTree(written.getLast().toFile());
    }

    private void writeFile(ObjectNode n) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(written.getLast().toFile(), n);
    }

    @Test
    void scoringOnlyOnTheParityFixtureIsClean() throws Exception {
        long a = frozenFixture();
        String before = storedState(a);
        var r = golden.check(a, null);
        assertThat(r.exit()).as(all(r)).isZero();
        assertThat(r.lines()).contains("0 differences");
        assertThat(storedState(a)).isEqualTo(before);
    }

    @Test
    void aChangedFeatureValueNamesTheDateAndColumn() throws Exception {
        long a = frozenFixture();
        LocalDate day = jdbc.sql("SELECT cycle_date FROM day_features WHERE athlete_id = ? AND ln_rmssd_z IS NOT NULL AND cycle_date > ? ORDER BY cycle_date LIMIT 1")
                .params(a, FIXTURE_FROM.plusDays(20)).query(LocalDate.class).single();
        jdbc.sql("UPDATE day_features SET ln_rmssd_z = ln_rmssd_z + 0.5 WHERE athlete_id = ? AND cycle_date = ?").params(a, day).update();

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isEqualTo(1);
        String line = lineFor(r, day);
        assertThat(line).contains("day_features: ln_rmssd_z").contains("derived_day_metrics:");
        // The header line carries the file name, freeze time and app version (e.g. "16:18:50.535Z"), never a value: skip it.
        assertThat(r.lines().get(0)).startsWith("Golden check, athlete");
        assertThat(String.join("\n", r.lines().subList(1, r.lines().size()))).doesNotContain("0.5");
    }

    @Test
    void aScorerThatComputesDifferentNumbersForTheSameModelIsACodeChange() throws Exception {
        long a = frozenFixture();
        // Stand-in for a code change in the scorer: same model, but its constant now gives other numbers.
        // (The model fingerprint in the file is set to the new coefficients, so only the scoring result differs.)
        var changed = coefficients.deepCopy();
        ((ObjectNode) changed.get("recovery")).put("intercept", changed.get("recovery").get("intercept").asDouble() + 5);
        jdbc.sql("UPDATE model_version SET coefficients = ?::jsonb WHERE athlete_id = ?").params(changed.toString(), a).update();
        var file = readFile(a);
        file.put("modelFingerprint", GoldenHasher.fingerprint(JSON, changed));
        writeFile(file);

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isEqualTo(1);
        assertThat(all(r)).contains("CODE CHANGED");
        assertThat(r.lines().stream().filter(l -> l.contains("derived_day_metrics:") && l.contains("recovery_pct")).count()).isPositive();
        assertThat(r.lines().stream().filter(l -> l.contains("day_features:")).count()).as("features did not move").isZero();
    }

    @Test
    void aChangedModelIsInputsChangedAndScoresAreNotCompared() throws Exception {
        long a = frozenFixture();
        var changed = coefficients.deepCopy();
        ((ObjectNode) changed.get("recovery")).put("intercept", changed.get("recovery").get("intercept").asDouble() + 5);
        jdbc.sql("UPDATE model_version SET coefficients = ?::jsonb WHERE athlete_id = ?").params(changed.toString(), a).update();

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isEqualTo(3);
        assertThat(all(r)).contains("INPUTS CHANGED").contains("coefficients of model m-golden changed").doesNotContain("CODE CHANGED");
    }

    @Test
    void aScoreThatDiffersFromTheFrozenHashNamesTheDayAndColumn() throws Exception {
        long a = frozenFixture();
        var file = readFile(a);
        ((ObjectNode) file.get("days").get("2026-09-20").get("scoreColumns")).put("recovery_pct", "0000000000000000");
        writeFile(file);

        var r = golden.check(a, null);

        assertThat(r.exit()).as(all(r)).isEqualTo(1);
        assertThat(lineFor(r, LocalDate.of(2026, 9, 20))).contains("derived_day_metrics: recovery_pct");
    }
}
