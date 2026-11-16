package app.training.metrics.trends;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/athletes/{id}/trends against a real database: access, values and gaps. Synthetic rows only. */
@AutoConfigureMockMvc
class TrendsIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    private CurrentUser login(Role role, Long athleteId) {
        long id = users.insert("u-trends-" + System.nanoTime(), "x", role, athleteId);
        return users.findById(id).orElseThrow();
    }

    private void activeModel(long athlete) {
        jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, 'm-trends', 'ACTIVE', 'f3', '{}'::jsonb, '{}', true, '')""").param(athlete).update();
    }

    private void features(long athlete, LocalDate d, Integer rmssd, Integer rhr, Double load) {
        jdbc.sql("""
                INSERT INTO day_features (athlete_id, cycle_date, feature_version, rmssd, rhr, sleep_minutes, day_load_edwards, has_garmin_core)
                VALUES (?, ?, 'f3', ?, ?, 430, ?, true)""").params(athlete, d, rmssd, rhr, load).update();
    }

    private void score(long athlete, LocalDate d, Integer recovery, String band, boolean lowCoverage) {
        jdbc.sql("""
                INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct, recovery_band,
                    strain, sleep_performance_pct, sleep_need_min, loads, contributors, data_quality)
                VALUES (?, ?, 'm-trends', 'f3', ?, ?, 9.4, 91, 450, '{}'::jsonb, '{}'::jsonb, ?::jsonb)""")
                .params(athlete, d, recovery, band, "{\"low_coverage\":" + lowCoverage + "}").update();
    }

    @Test
    void returnsStoredValuesWithGapsAsNullAndNoModelIsEmpty() throws Exception {
        long a = newAthlete("Trends A");
        mvc.perform(get("/api/athletes/" + a + "/trends").with(user(login(Role.COACH, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(0))
                .andExpect(jsonPath("$.modelVersion").doesNotExist());

        activeModel(a);
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Bucharest"));
        features(a, today.minusDays(3), 55, 48, 0.0);
        features(a, today.minusDays(1), null, null, null); // a features row with nothing recorded
        score(a, today.minusDays(3), 71, "GREEN", false);
        score(a, today.minusDays(2), null, null, true);
        mvc.perform(get("/api/athletes/" + a + "/trends").with(user(login(Role.ATHLETE, a))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").value("m-trends"))
                .andExpect(jsonPath("$.firstHrvDate").value(today.minusDays(3).toString()))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].rmssd").value(55.0))
                .andExpect(jsonPath("$.days[0].dayLoadEdwards").value(0.0))
                .andExpect(jsonPath("$.days[0].recoveryPct").value(71))
                .andExpect(jsonPath("$.days[1].strainLowCoverage").value(true))
                .andExpect(jsonPath("$.days[1].rmssd").doesNotExist())
                .andExpect(jsonPath("$.days[2].dayLoadEdwards").doesNotExist());
    }

    @Test
    void anAthleteSeesOnlyTheirOwn() throws Exception {
        long a = newAthlete("Trends B");
        long other = newAthlete("Trends C");
        mvc.perform(get("/api/athletes/" + other + "/trends").with(user(login(Role.ATHLETE, a))))
                .andExpect(status().isForbidden());
    }
}
