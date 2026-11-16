package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Daily scoring against a real database, on the synthetic "load" parity fixture: what is
 * written, which rows a date-range recompute replaces, and the data-quality flags.
 */
@AutoConfigureMockMvc
class ScoringIT extends IntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ScoringService scoring;

    CurrentUser coach;
    JsonNode expected;

    @BeforeEach
    void setUp() throws Exception {
        long id = users.insert("coach-s" + System.nanoTime(), "x", Role.COACH, null);
        coach = users.findById(id).orElseThrow();
        expected = JSON.readTree(DailyScorerParityTest.resource("load", "expected.json"));
    }

    /** An athlete whose profile matches the fixture's feature_config (HRmax 190, HRrest 50), with its day_features rows. */
    private long athleteWithFeatures() throws Exception {
        long a = jdbc.sql("INSERT INTO athlete (name, sex, hr_max, resting_hr) VALUES ('S', 'M', 190, 50) RETURNING id")
                .query(Long.class).single();
        for (var row : DailyScorerParityTest.readFeatures("load")) {
            var cols = new ArrayList<String>(ScoringRepository.INPUT_COLUMNS);
            var sql = "INSERT INTO day_features (athlete_id, cycle_date, feature_version, has_garmin_core, " + String.join(", ", cols)
                    + ") VALUES (?, ?, 'f3', ?" + ", ?".repeat(cols.size()) + ")";
            var params = new ArrayList<Object>();
            params.add(a);
            params.add(row.date());
            params.add(row.hasGarminCore());
            cols.forEach(c -> params.add(row.values().get(c)));
            jdbc.sql(sql).params(params).update();
        }
        return a;
    }

    private void activeModel(long athlete, String version, JsonNode coefficients) {
        jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, ?, 'ACTIVE', 'f3', ?::jsonb, '{}', true, '')""")
                .params(athlete, version, coefficients.toString()).update();
    }

    private Map<String, Object> stored(long athlete, String date) {
        return jdbc.sql("SELECT * FROM derived_day_metrics WHERE athlete_id = ? AND model_version = 'm-test' AND cycle_date = ?")
                .params(athlete, LocalDate.parse(date)).query().singleRow();
    }

    private JsonNode quality(long athlete, String date) throws Exception {
        return JSON.readTree(stored(athlete, date).get("data_quality").toString());
    }

    @Test
    void recomputeWritesEveryGridDayWithTheReferenceValues() throws Exception {
        long a = athleteWithFeatures();
        activeModel(a, "m-test", expected.get("coefficients"));

        mvc.perform(post("/api/athletes/" + a + "/scores/recompute").with(user(coach)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("m-test"))
                .andExpect(jsonPath("$.days").value(60))
                .andExpect(jsonPath("$.lastDataDate").value("2026-10-27"))
                .andExpect(jsonPath("$.profileChanged").value(false));

        assertThat(count("SELECT count(*) FROM derived_day_metrics WHERE athlete_id = ? AND feature_version = 'f3'", a)).isEqualTo(60);
        for (JsonNode day : expected.get("days")) {
            var row = stored(a, day.get("date").asText());
            var s = day.get("stored");
            String at = day.get("date").asText();
            assertThat(row.get("recovery_pct")).as(at).isEqualTo(s.get("recovery").isNull() ? null : s.get("recovery").asInt());
            assertThat(row.get("recovery_band")).as(at).isEqualTo(day.get("band").isNull() ? null : day.get("band").asText());
            assertThat(row.get("strain")).as(at).isEqualTo(s.get("strain").isNull() ? null : new BigDecimal(s.get("strain").asText()));
            assertThat(row.get("sleep_need_min")).as(at).isEqualTo(s.get("sleep_need").asInt());
            assertThat(row.get("sleep_performance_pct")).as(at).isEqualTo(s.get("sleep_perf").isNull() ? null : s.get("sleep_perf").asInt());
        }

        // Planted edge days carry their reasons and flags.
        var planted = expected.get("planted");
        var missing = quality(a, planted.get("missing_day").asText());
        assertThat(missing.get("has_features").asBoolean()).isFalse();
        assertThat(missing.get("recovery_reasons").get(0).get("reason").asText()).isEqualTo("NO_DAY_DATA");
        var morningAfter = quality(a, planted.get("no_strain_yesterday").asText());
        assertThat(morningAfter.get("recovery_reasons").get(0).get("reason").asText()).isEqualTo("NO_STRAIN_YESTERDAY");
        assertThat(morningAfter.get("recovery_reasons").get(0).get("detail").asText()).isEqualTo("MISSING_WORKOUT_HR");
        assertThat(quality(a, planted.get("low_coverage").asText()).get("low_coverage").asBoolean()).isTrue();
        var lowYesterday = quality(a, planted.get("low_coverage_yesterday").asText());
        assertThat(lowYesterday.get("recovery_lower_confidence").asBoolean()).isTrue();
        assertThat(lowYesterday.get("lower_confidence_reasons").get(0).asText()).isEqualTo("LOW_COVERAGE_YESTERDAY");
        assertThat(quality(a, planted.get("no_all_day_hr").asText()).get("strain_reason").asText()).isEqualTo("NO_ALL_DAY_HR");
        assertThat(quality(a, planted.get("no_sleep").asText()).get("sleep_performance_reason").asText()).isEqualTo("NO_SLEEP");

        // Strain above 21 in raw form is also outside the strain training range.
        var hard = quality(a, planted.get("strain_above_21").asText());
        assertThat(hard.get("drift").findValuesAsText("part")).contains("strain");

        // Contributions explain the recovery: intercept + points = raw recovery.
        var row = stored(a, "2026-09-03");
        var contributors = JSON.readTree(row.get("contributors").toString());
        double sum = contributors.get("intercept").asDouble();
        for (var t : contributors.get("terms")) sum += t.get("points").asDouble();
        assertThat(sum).isCloseTo(contributors.get("recovery_raw").asDouble(), org.assertj.core.api.Assertions.within(1e-9));
        assertThat(JSON.readTree(row.get("loads").toString()).get("load_column").asText()).isEqualTo("day_load_banister");

        mvc.perform(get("/api/athletes/" + a + "/scores?from=2026-10-01&to=2026-10-03").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].date").value("2026-10-01"))
                .andExpect(jsonPath("$[0].featureVersion").value("f3"));
    }

    @Test
    void aDateRangeReplacesOnlyThoseDaysButUsesEarlierDays() throws Exception {
        long a = athleteWithFeatures();
        activeModel(a, "m-test", expected.get("coefficients"));
        scoring.recompute(a, null, null);
        // A row from an earlier model is history and must survive.
        jdbc.sql("INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct) VALUES (?, '2026-10-02', 'm-old', 'f3', 12)")
                .param(a).update();
        jdbc.sql("UPDATE derived_day_metrics SET computed_at = '2000-01-01' WHERE athlete_id = ?").param(a).update();

        var r = scoring.recompute(a, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-05"));

        assertThat(r.days()).isEqualTo(5);
        assertThat(r.from()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(count("SELECT count(*) FROM derived_day_metrics WHERE athlete_id = ? AND model_version = 'm-test'", a)).isEqualTo(60);
        assertThat(count("SELECT count(*) FROM derived_day_metrics WHERE athlete_id = ? AND computed_at > '2001-01-01'", a)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM derived_day_metrics WHERE athlete_id = ? AND model_version = 'm-old'", a)).isEqualTo(1);
        // 10-01 still sees yesterday's strain and the previous week's sleep.
        var day = expected.get("days").findParents("date").stream().filter(d -> d.get("date").asText().equals("2026-10-01")).findFirst().orElseThrow();
        assertThat(stored(a, "2026-10-01").get("sleep_need_min")).isEqualTo(day.get("stored").get("sleep_need").asInt());
        assertThat(JSON.readTree(stored(a, "2026-10-01").get("loads").toString()).get("prev_strain").asDouble())
                .isCloseTo(day.get("prev_strain").asDouble(), org.assertj.core.api.Assertions.within(1e-9));

        // A range beyond the last data date writes nothing (stale data has no score, ADR 0008 C2).
        var after = scoring.recompute(a, LocalDate.parse("2026-11-01"), null);
        assertThat(after.days()).isZero();
        assertThat(after.lastDataDate()).isEqualTo(LocalDate.parse("2026-10-27"));
    }

    @Test
    void flagsProfileChangesAndStaleFeatures() throws Exception {
        long a = athleteWithFeatures();
        activeModel(a, "m-test", expected.get("coefficients"));
        jdbc.sql("UPDATE athlete SET hr_max = 186 WHERE id = ?").param(a).update();
        // An import finished after the features were computed: they may be out of date.
        jdbc.sql("""
                INSERT INTO import_batch (athlete_id, kind, status, created_at, finished_at)
                VALUES (?, 'GARMIN_EXPORT', 'SUCCEEDED', now() + interval '1 hour', now() + interval '1 hour')""").param(a).update();

        var r = scoring.recompute(a, null, null);

        assertThat(r.profileChanged()).isTrue();
        assertThat(r.stale()).isEqualTo(59); // every day with a features row; the planted missing day has none
        var q = quality(a, "2026-09-03");
        assertThat(q.get("profile_changed").asBoolean()).isTrue();
        assertThat(q.get("profile").get("model_hr_max").asInt()).isEqualTo(190);
        assertThat(q.get("profile").get("now_hr_max").asInt()).isEqualTo(186);
        assertThat(q.get("stale").asBoolean()).isTrue();
    }

    @Test
    void refusesWithoutAUsableActiveModel() throws Exception {
        long a = athleteWithFeatures();
        mvc.perform(post("/api/athletes/" + a + "/scores/recompute").with(user(coach)).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(scoring.recomputeIfPossible(a).skipped()).contains("No ACTIVE model");

        var old = (com.fasterxml.jackson.databind.node.ObjectNode) expected.get("coefficients").deepCopy();
        old.put("schema_version", 1);
        activeModel(a, "m-old", old);
        mvc.perform(post("/api/athletes/" + a + "/scores/recompute").with(user(coach)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("schema_version")));
        assertThat(count("SELECT count(*) FROM derived_day_metrics WHERE athlete_id = ?", a)).isZero();

        mvc.perform(post("/api/athletes/" + a + "/scores/recompute?from=2026-10-05&to=2026-10-01").with(user(coach)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheCoachMayRecompute() throws Exception {
        long a = athleteWithFeatures();
        long athleteUser = users.insert("ath-s" + System.nanoTime(), "x", Role.ATHLETE, a);
        var ath = users.findById(athleteUser).orElseThrow();
        mvc.perform(post("/api/athletes/" + a + "/scores/recompute").with(user(ath)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/athletes/" + a + "/scores").with(user(ath))).andExpect(status().isOk());
    }
}
