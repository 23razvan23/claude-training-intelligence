package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.ingestion.Fixtures;
import app.training.ingestion.ImportBatchRepository;
import app.training.ingestion.ImportKind;
import app.training.ingestion.ImportRunner;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Stage 1b-3: the loop the coach repeats every week or two until Whoop ends, end to end on
 * synthetic exports. Import a Whoop export (taken during the day, so its last cycle is
 * unfinished) and a Garmin export, press Recompute features, read the Holdout. Then the same
 * two weeks later with overlapping exports: nothing is stored twice, the formerly unfinished
 * Whoop day joins, and the window grows.
 */
@AutoConfigureMockMvc
class HoldoutFeedIT extends IntegrationTest {
    static final LocalDate FIRST = LocalDate.of(2026, 2, 1);
    /** The model's test window ends on FIRST+19, so the holdout starts on FIRST+20 (14 baseline days before it). */
    static final LocalDate WINDOW_FROM = FIRST.plusDays(20);

    @Autowired MockMvc mvc;
    @Autowired ImportRunner runner;
    @Autowired ImportBatchRepository batches;
    @Autowired UserRepository users;
    @TempDir Path dir;

    CurrentUser coach;
    long athlete;

    @BeforeEach
    void setUp() throws Exception {
        coach = users.findById(users.insert("coach-f" + System.nanoTime(), "x", Role.COACH, null)).orElseThrow();
        athlete = jdbc.sql("INSERT INTO athlete (name, sex, hr_max, resting_hr) VALUES ('F', 'M', 190, 48) RETURNING id")
                .query(Long.class).single();
        var coefficients = DailyScorerParityTest.asContract(new ObjectMapper()
                .readTree(DailyScorerParityTest.resource("adr0008", "expected.json")).get("coefficients"));
        long model = jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, feature_version, coefficients, metrics, passed, report_html, test_from, test_to)
                VALUES (?, 'm-feed', 'f3', ?::jsonb, ?::jsonb, true, '', ?, ?) RETURNING id""")
                .params(athlete, coefficients.toString(),
                        "{\"targets\": {\"strain_mae_max\": 2.0, \"strain_spearman_min\": 0.8, \"recovery_band_agreement_min\": 0.7, "
                                + "\"recovery_green_red_max\": 0.03}}",
                        FIRST, WINDOW_FROM.minusDays(1))
                .query(Long.class).single();
        mvc.perform(post("/api/models/" + model + "/activate").with(user(coach)).with(csrf())).andExpect(status().isOk());
    }

    private com.fasterxml.jackson.databind.JsonNode importExport(ImportKind kind, Path file) throws Exception {
        Path copy = Files.createTempFile(dir, "upload", file.getFileName().toString());
        Files.copy(file, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        long id = batches.create(athlete, kind, "test", null, coach.id());
        runner.run(id, athlete, kind, List.of(copy), null);
        var b = batches.find(id).orElseThrow();
        assertThat(b.status()).isEqualTo("SUCCEEDED");
        return b.stats();
    }

    /** One round: a Whoop export of {@code whoopDays} days (the last cycle unfinished) and a Garmin export of {@code garminDays}. */
    private com.fasterxml.jackson.databind.JsonNode round(String name, int whoopDays, int garminDays) throws Exception {
        Path d = Files.createDirectories(dir.resolve(name));
        Fixtures.whoopCsvs(d, FIRST, whoopDays, true, "3.1");
        var whoopStats = importExport(ImportKind.WHOOP_EXPORT, d.resolve("physiological_cycles.csv"));
        importExport(ImportKind.GARMIN_EXPORT, Fixtures.garminExport(d, FIRST, garminDays));
        return whoopStats;
    }

    private void recompute() throws Exception {
        mvc.perform(post("/api/athletes/" + athlete + "/features/recompute").with(user(coach)).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void everyFewWeeksTheSameStepsExtendTheComparisonWithoutDuplicates() throws Exception {
        // Round 1: Whoop to FIRST+25 (FIRST+25 still running), Garmin to FIRST+25.
        round("r1", 26, 26);
        mvc.perform(get("/api/athletes/" + athlete + "/holdout/feed").with(user(coach)))
                .andExpect(jsonPath("$.lastWhoopDay").value(FIRST.plusDays(24).toString()))
                .andExpect(jsonPath("$.lastGarminDay").value(FIRST.plusDays(25).toString()))
                .andExpect(jsonPath("$.recomputeNeeded").value(true))
                .andExpect(jsonPath("$.whoopEnd").value("2027-02-19"));
        recompute();
        mvc.perform(get("/api/athletes/" + athlete + "/holdout/feed").with(user(coach)))
                .andExpect(jsonPath("$.recomputeNeeded").value(false))
                .andExpect(jsonPath("$.lastScoredDay").value(FIRST.plusDays(25).toString()));
        mvc.perform(get("/api/athletes/" + athlete + "/holdout").with(user(coach)))
                .andExpect(jsonPath("$.windowFrom").value(WINDOW_FROM.toString()))
                .andExpect(jsonPath("$.windowTo").value(FIRST.plusDays(24).toString()))
                .andExpect(jsonPath("$.days.length()").value(5))
                .andExpect(jsonPath("$.cumulative.recovery.n").value(5))
                .andExpect(jsonPath("$.verdict.state").value("NOT_ENOUGH_DAYS_YET"));

        // Round 2, two weeks later: both exports overlap the first ones.
        var whoop2 = round("r2", 36, 36);
        // Only the new finished cycles are stored, the formerly unfinished FIRST+25 among them; none twice.
        assertThat(whoop2.path("whoop_reference").path("inserted").asLong()).isEqualTo(10);
        assertThat(whoop2.path("whoop_reference").path("duplicates").asLong()).isEqualTo(25);
        assertThat(count("SELECT count(*) FROM whoop_reference WHERE athlete_id = ?", athlete)).isEqualTo(35);
        assertThat(count("SELECT count(*) FROM (SELECT cycle_date FROM whoop_reference WHERE athlete_id = ? GROUP BY cycle_date HAVING count(*) > 1) x",
                athlete)).isZero();
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ? AND source_id LIKE '%@%'", athlete)).isZero();

        // Before Recompute features: FIRST+25 (unfinished in the first Whoop export, already scored from the
        // first Garmin export) joins at once; the newer Whoop days wait as "no score from us yet".
        mvc.perform(get("/api/athletes/" + athlete + "/holdout").with(user(coach)))
                .andExpect(jsonPath("$.windowTo").value(FIRST.plusDays(34).toString()))
                .andExpect(jsonPath("$.cumulative.recovery.n").value(6))
                .andExpect(jsonPath("$.cumulative.groups.recovery.NO_SCORE_YET").value(9));
        mvc.perform(get("/api/athletes/" + athlete + "/holdout/feed").with(user(coach)))
                .andExpect(jsonPath("$.recomputeNeeded").value(true));

        recompute();
        mvc.perform(get("/api/athletes/" + athlete + "/holdout").with(user(coach)))
                .andExpect(jsonPath("$.windowTo").value(FIRST.plusDays(34).toString()))
                .andExpect(jsonPath("$.days.length()").value(15))
                .andExpect(jsonPath("$.cumulative.recovery.n").value(15))
                .andExpect(jsonPath("$.cumulative.groups.recovery.COMPARED").value(15))
                .andExpect(jsonPath("$.markers.recomputeRuns.length()").value(2));
    }
}
