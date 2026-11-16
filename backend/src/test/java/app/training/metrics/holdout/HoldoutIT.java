package app.training.metrics.holdout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The live comparison against a real database, on synthetic rows: which rows it reads (the
 * version's own scores, finished Whoop cycles only), coach-only and read-only access, the
 * restart on a new activation with the retired version's comparison kept as it was, and the
 * Recompute features log (ADR 0009 H1, H2, H8; ADR 0013).
 */
@AutoConfigureMockMvc
class HoldoutIT extends IntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate FROM = LocalDate.parse("2026-11-09");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    CurrentUser coach;
    JsonNode coefficients;

    @BeforeEach
    void setUp() throws Exception {
        coach = users.findById(users.insert("coach-h" + System.nanoTime(), "x", Role.COACH, null)).orElseThrow();
        try (var in = getClass().getResourceAsStream("/parity/load/expected.json")) {
            coefficients = JSON.readTree(in).get("coefficients");
        }
    }

    private static final String TARGETS = """
            {"targets": {"strain_mae_max": 2.0, "strain_spearman_min": 0.8,
                         "recovery_band_agreement_min": 0.7, "recovery_green_red_max": 0.03}}""";

    /** A passed candidate with a test window ending the day before {@code windowFrom}. */
    private long candidate(long athlete, String version, LocalDate windowFrom) {
        return jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, feature_version, coefficients, metrics, passed, report_html, test_from, test_to)
                VALUES (?, ?, 'f3', ?::jsonb, ?::jsonb, true, '', ?, ?) RETURNING id""")
                .params(athlete, version, coefficients.toString(), TARGETS, windowFrom.minusDays(60), windowFrom.minusDays(1))
                .query(Long.class).single();
    }

    private void activate(long modelId) throws Exception {
        mvc.perform(post("/api/models/" + modelId + "/activate").with(user(coach)).with(csrf())).andExpect(status().isOk());
    }

    /** A stored score for a day: recovery r (null: blank with NO_HRV), strain s with full coverage. */
    private void score(long athlete, String version, LocalDate d, Integer r, double s) {
        String band = r == null ? null : r >= 67 ? "GREEN" : r <= 33 ? "RED" : "YELLOW";
        String quality = r == null
                ? "{\"has_features\": true, \"low_coverage\": false, \"recovery_reasons\": [{\"reason\": \"NO_HRV\", \"detail\": null}], "
                        + "\"recovery_lower_confidence\": false, \"drift\": [], \"profile_changed\": false, \"stale\": false}"
                : "{\"has_features\": true, \"low_coverage\": false, \"recovery_reasons\": [], \"recovery_lower_confidence\": false, "
                        + "\"drift\": [], \"profile_changed\": false, \"stale\": false}";
        String contributors = r == null ? "{\"intercept\": 50, \"terms\": {}}" : "{\"intercept\": 50, \"terms\": {}, \"recovery_raw\": " + r + "}";
        jdbc.sql("""
                INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct, recovery_band,
                    strain, sleep_performance_pct, sleep_need_min, loads, contributors, data_quality)
                VALUES (?, ?, ?, 'f3', ?, ?, ?, 85, 480, ?::jsonb, ?::jsonb, ?::jsonb)""")
                .params(athlete, d, version, r, band, s,
                        "{\"monitored_minutes\": 900, \"strain\": " + s + ", \"sleep_need\": 480, \"sleep_performance\": 85}",
                        contributors, quality)
                .update();
    }

    /** A Whoop cycle for wake day d (sleep onset the evening before); open (unfinished) when {@code finished} is false. */
    private void whoop(long athlete, LocalDate d, int recovery, Double strain, boolean finished) {
        var start = d.minusDays(1).atTime(23, 0).toInstant(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO whoop_reference (athlete_id, cycle_start, cycle_end, cycle_date, recovery_pct, day_strain,
                    sleep_performance_pct, sleep_need_min)
                VALUES (?, ?, ?, ?, ?, ?, 80, 470)""")
                .params(athlete, java.sql.Timestamp.from(start), finished ? java.sql.Timestamp.from(start.plusSeconds(86_400)) : null,
                        d, recovery, strain)
                .update();
    }

    private long athlete() {
        return jdbc.sql("INSERT INTO athlete (name, sex, hr_max, resting_hr, timezone) VALUES ('H', 'M', 190, 50, 'UTC') RETURNING id")
                .query(Long.class).single();
    }

    @Test
    void comparesTheActiveVersionsOwnScoresWithFinishedWhoopCycles() throws Exception {
        long a = athlete();
        long m = candidate(a, "m-a", FROM);
        activate(m);
        for (int i = -3; i < 6; i++) {
            score(a, "m-a", FROM.plusDays(i), i == 2 ? null : 75, 10.0);
            // Another version's rows on the same days are never read.
            score(a, "m-other", FROM.plusDays(i), 10, 20.0);
        }
        for (int i = -3; i < 5; i++) whoop(a, FROM.plusDays(i), 70, 9.5, true);
        whoop(a, FROM.plusDays(5), 70, 3.0, false);   // today's unfinished cycle: never compared

        mvc.perform(get("/api/athletes/" + a + "/holdout").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("m-a"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.protocol").value("ADR 0009"))
                .andExpect(jsonPath("$.source").value("activation"))
                .andExpect(jsonPath("$.windowFrom").value("2026-11-09"))
                .andExpect(jsonPath("$.windowTo").value("2026-11-13"))
                .andExpect(jsonPath("$.whoopEnd").value("2027-02-19"))
                .andExpect(jsonPath("$.days.length()").value(5))
                .andExpect(jsonPath("$.cumulative.groups.recovery.COMPARED").value(4))
                .andExpect(jsonPath("$.cumulative.groups.recovery.NO_HRV").value(1))
                .andExpect(jsonPath("$.cumulative.recovery.bandAgreement.k").value(4))
                .andExpect(jsonPath("$.cumulative.recovery.bandAgreement.n").value(4))
                .andExpect(jsonPath("$.cumulative.recovery.greenRed.high").isNumber())
                .andExpect(jsonPath("$.cumulative.strain.mae.value").value(0.5))
                .andExpect(jsonPath("$.marks.recovery_green_red_max").value(0.03))
                .andExpect(jsonPath("$.marks.min_recovery_days").value(90))
                .andExpect(jsonPath("$.verdict.state").value("NOT_ENOUGH_DAYS_YET"))
                .andExpect(jsonPath("$.verdict.recoveryDays").value(4))
                .andExpect(jsonPath("$.naive.n").value(4));
    }

    @Test
    void theDecisionReportDownloadsAsStaticHtml() throws Exception {
        long a = athlete();
        activate(candidate(a, "m-a", FROM));
        score(a, "m-a", FROM, 75, 10.0);
        whoop(a, FROM, 70, 9.5, true);
        var res = mvc.perform(get("/api/athletes/" + a + "/holdout/report").with(user(coach)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(res.getContentType()).startsWith("text/html");
        assertThat(res.getHeader("Content-Disposition")).startsWith("attachment").contains("holdout-decision-m-a-");
        assertThat(res.getHeader("Content-Security-Policy")).contains("default-src 'none'").contains("sandbox");
        assertThat(res.getContentAsString()).contains("Not enough days yet: 1 of 90");

        var athleteUser = users.findById(users.insert("ath-r" + System.nanoTime(), "x", Role.ATHLETE, a)).orElseThrow();
        mvc.perform(get("/api/athletes/" + a + "/holdout/report").with(user(athleteUser))).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheCoachMaySeeItAndNothingCanBeChanged() throws Exception {
        long a = athlete();
        activate(candidate(a, "m-a", FROM));
        var athleteUser = users.findById(users.insert("ath-h" + System.nanoTime(), "x", Role.ATHLETE, a)).orElseThrow();
        mvc.perform(get("/api/athletes/" + a + "/holdout").with(user(athleteUser))).andExpect(status().isForbidden());
        mvc.perform(get("/api/athletes/" + a + "/holdout/versions").with(user(athleteUser))).andExpect(status().isForbidden());
        for (var req : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
            post("/api/athletes/" + a + "/holdout"), put("/api/athletes/" + a + "/holdout"), delete("/api/athletes/" + a + "/holdout")}) {
            int code = mvc.perform(req.with(user(coach)).with(csrf())).andReturn().getResponse().getStatus();
            assertThat(code).as(req.toString()).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void withoutAnActiveModelThereIsNoComparison() throws Exception {
        long a = athlete();
        mvc.perform(get("/api/athletes/" + a + "/holdout").with(user(coach))).andExpect(status().isConflict());
        mvc.perform(get("/api/athletes/" + a + "/holdout?version=nope").with(user(coach))).andExpect(status().isNotFound());
    }

    @Test
    void aNewActivationRestartsTheCountAndKeepsTheRetiredComparisonAsItWas() throws Exception {
        long a = athlete();
        long first = candidate(a, "m-a", FROM);
        activate(first);
        for (int i = 0; i < 10; i++) {
            score(a, "m-a", FROM.plusDays(i), 75, 10.0);
            whoop(a, FROM.plusDays(i), 70, 9.5, true);
        }
        String before = mvc.perform(get("/api/athletes/" + a + "/holdout").with(user(coach)))
                .andExpect(jsonPath("$.cumulative.recovery.n").value(10))
                .andReturn().getResponse().getContentAsString();

        // A later calibration, tested up to FROM+6, is activated: its window starts on FROM+7 with its own marks.
        long second = candidate(a, "m-b", FROM.plusDays(7));
        activate(second);
        mvc.perform(get("/api/athletes/" + a + "/holdout").with(user(coach)))
                .andExpect(jsonPath("$.modelVersion").value("m-b"))
                .andExpect(jsonPath("$.windowFrom").value("2026-11-16"))
                .andExpect(jsonPath("$.cumulative.recovery.n").value(0))                 // m-b has no scores yet
                .andExpect(jsonPath("$.cumulative.groups.recovery.NO_SCORE_YET").value(3));

        // More Whoop data arrives after the retirement, and m-a's old rows are still there.
        whoop(a, FROM.plusDays(10), 70, 9.5, true);
        whoop(a, FROM.plusDays(11), 70, 9.5, true);
        String retired = mvc.perform(get("/api/athletes/" + a + "/holdout?version=m-a").with(user(coach)))
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.phase").value("RETIRED"))
                .andExpect(jsonPath("$.windowTo").value("2026-11-18"))
                .andExpect(jsonPath("$.cumulative.recovery.n").value(10))
                .andExpect(jsonPath("$.verdict.state").value("NOT_ENOUGH_DAYS_BY_RULE"))
                .andReturn().getResponse().getContentAsString();
        // Identical figures to the moment before the new activation (only the status fields moved).
        JsonNode b = JSON.readTree(before), r = JSON.readTree(retired);
        assertThat(r.get("cumulative")).isEqualTo(b.get("cumulative"));
        assertThat(r.get("days")).isEqualTo(b.get("days"));
        assertThat(r.get("naive")).isEqualTo(b.get("naive"));

        mvc.perform(get("/api/athletes/" + a + "/holdout/versions").with(user(coach)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value("m-b"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].version").value("m-a"))
                .andExpect(jsonPath("$[1].status").value("RETIRED"))
                .andExpect(jsonPath("$[1].windowFrom").value("2026-11-09"));
    }

    @Test
    void everyRecomputeFeaturesRunIsLoggedAndMarked() throws Exception {
        long a = athlete();
        activate(candidate(a, "m-a", FROM));
        score(a, "m-a", FROM, 75, 10.0);
        whoop(a, FROM, 70, 9.5, true);

        mvc.perform(post("/api/athletes/" + a + "/features/recompute").with(user(coach)).with(csrf())).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM feature_recompute_log WHERE athlete_id = ? AND ran_by = ? AND feature_version = 'f3'",
                a, coach.id())).isEqualTo(1);
        mvc.perform(get("/api/athletes/" + a + "/holdout").with(user(coach)))
                .andExpect(jsonPath("$.markers.recomputeRuns.length()").value(1));
    }
}
