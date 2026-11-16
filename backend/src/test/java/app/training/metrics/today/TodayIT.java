package app.training.metrics.today;

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

/** GET /api/athletes/{id}/today against a real database: access and the stale state. */
@AutoConfigureMockMvc
class TodayIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    private CurrentUser login(Role role, Long athleteId) {
        long id = users.insert("u-today-" + System.nanoTime(), "x", role, athleteId);
        return users.findById(id).orElseThrow();
    }

    private void activeModel(long athlete) {
        jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, 'm-today', 'ACTIVE', 'f3', '{}'::jsonb, '{}', true, '')""").param(athlete).update();
    }

    private void storedDay(long athlete, LocalDate d, Integer recovery, String band) {
        jdbc.sql("""
                INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct, recovery_band,
                    strain, sleep_performance_pct, sleep_need_min, loads, contributors, data_quality)
                VALUES (?, ?, 'm-today', 'f3', ?, ?, 9.4, 91, 450, '{}'::jsonb, '{"intercept":55,"terms":{}}'::jsonb,
                    '{"has_features":true,"low_coverage":false,"recovery_reasons":[],"recovery_lower_confidence":false,"drift":[],"stale":false}'::jsonb)""")
                .params(athlete, d, recovery, band).update();
    }

    @Test
    void athleteSeesOwnStaleTodayAndNeverYesterdayAsToday() throws Exception {
        long a = newAthlete("Today A");
        activeModel(a);
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Bucharest"));
        storedDay(a, today.minusDays(1), 81, "GREEN");
        mvc.perform(get("/api/athletes/" + a + "/today").with(user(login(Role.ATHLETE, a))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.day").doesNotExist())
                .andExpect(jsonPath("$.lastScored.date").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.lastScored.recoveryPct").value(81));
    }

    @Test
    void coachSeesAnyAthleteButAnAthleteOnlyThemselves() throws Exception {
        long a = newAthlete("Today B");
        long other = newAthlete("Today C");
        mvc.perform(get("/api/athletes/" + a + "/today").with(user(login(Role.COACH, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_MODEL"));
        mvc.perform(get("/api/athletes/" + other + "/today").with(user(login(Role.ATHLETE, a))))
                .andExpect(status().isForbidden());
    }

    @Test
    void freshWhenTodayIsScored() throws Exception {
        long a = newAthlete("Today D");
        activeModel(a);
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Bucharest"));
        storedDay(a, today, 70, "GREEN");
        mvc.perform(get("/api/athletes/" + a + "/today").with(user(login(Role.COACH, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FRESH"))
                .andExpect(jsonPath("$.day.recoveryPct").value(70));
    }
}
