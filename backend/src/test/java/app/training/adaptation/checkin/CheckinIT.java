package app.training.adaptation.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.common.ApiException;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Stage 2-1b (ADR 0019) against a real database: append-only, latest wins per scope, who may log. */
@AutoConfigureMockMvc
class CheckinIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CheckinService service;
    @Autowired Clock clock;

    private CurrentUser login(Role role, Long athleteId) {
        long id = users.insert("u-checkin-" + System.nanoTime(), "x", role, athleteId);
        return users.findById(id).orElseThrow();
    }

    private LocalDate today(long athlete) {
        return service.today(athlete);
    }

    private CheckinRequest day(Integer rpe, Integer soreness) {
        return new CheckinRequest(null, null, null, rpe, soreness, null, null, false, false, false, null);
    }

    private long activity(long athlete, LocalDate date) {
        return jdbc.sql("""
                INSERT INTO activity (athlete_id, source, source_id, sport, start_at, calendar_date, duration_s)
                VALUES (?, 'GARMIN', ?, 'running', ?::timestamptz, ?, 3600) RETURNING id""")
                .params(athlete, "a" + System.nanoTime(), date + "T07:00:00Z", date).query(Long.class).single();
    }

    private long session(long athlete, LocalDate date) {
        return jdbc.sql("""
                INSERT INTO planned_session (athlete_id, session_date, session_type, demand, title)
                VALUES (?, ?, 'threshold', 'KEY', 'Tempo') RETURNING id""").params(athlete, date).query(Long.class).single();
    }

    @Test
    void aCorrectionIsANewRowAndTheLatestWins() {
        long a = newAthlete("Latest wins");
        var me = login(Role.ATHLETE, a);
        service.create(a, day(5, 2), me);
        var second = service.create(a, day(8, 4), me);

        assertThat(count("SELECT count(*) FROM subjective_log WHERE athlete_id = ?", a)).as("both rows are kept").isEqualTo(2);
        var current = service.recent(a, 3);
        assertThat(current).hasSize(1);
        assertThat(current.get(0).id()).isEqualTo(second.id());
        assertThat(current.get(0).rpe()).isEqualTo(8);
        assertThat(current.get(0).scope()).isEqualTo("day");
    }

    @Test
    void dayActivityAndSessionAreSeparateScopes() {
        long a = newAthlete("Scopes");
        var me = login(Role.ATHLETE, a);
        LocalDate d = today(a);
        long act = activity(a, d);
        long ses = session(a, d);

        service.create(a, day(3, 2), me);
        service.create(a, new CheckinRequest(null, null, act, 7, null, null, null, false, false, false, null), me);
        service.create(a, new CheckinRequest(null, ses, null, 9, null, null, null, false, false, false, null), me);
        service.create(a, new CheckinRequest(null, ses, null, 6, null, null, null, false, false, false, null), me); // replaces the 9

        var current = service.recent(a, 3);
        assertThat(current).extracting(CheckinView::scope).containsExactlyInAnyOrder("day", "activity", "session");
        assertThat(current.stream().filter(c -> c.scope().equals("session")).findFirst().orElseThrow().rpe()).isEqualTo(6);
        assertThat(current.stream().filter(c -> c.scope().equals("activity")).findFirst().orElseThrow().rpe()).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM subjective_log WHERE athlete_id = ?", a)).isEqualTo(4);
    }

    @Test
    void anActivityOrSessionFixesTheDayAndMustBelongToTheAthlete() {
        long a = newAthlete("Own scope");
        long other = newAthlete("Someone else");
        var me = login(Role.ATHLETE, a);
        LocalDate yesterday = today(a).minusDays(1);
        long act = activity(a, yesterday);

        var saved = service.create(a, new CheckinRequest(null, null, act, 7, null, null, null, false, false, false, null), me);
        assertThat(saved.date()).as("the activity's day, not today").isEqualTo(yesterday);

        long foreign = activity(other, yesterday);
        assertThatThrownBy(() -> service.create(a,
                new CheckinRequest(null, null, foreign, 7, null, null, null, false, false, false, null), me))
                .isInstanceOf(ApiException.class);
        long foreignSession = session(other, yesterday);
        assertThatThrownBy(() -> service.create(a,
                new CheckinRequest(null, foreignSession, null, 7, null, null, null, false, false, false, null), me))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void checkInsCanNotBeUpdatedOrDeletedButCascadesStillWork() {
        long a = newAthlete("Append only");
        var me = login(Role.ATHLETE, a);
        LocalDate d = today(a);
        long act = activity(a, d);
        service.create(a, new CheckinRequest(null, null, act, 5, null, null, null, false, false, false, null), me);

        assertThatThrownBy(() -> jdbc.sql("UPDATE subjective_log SET rpe = 9 WHERE athlete_id = ?").param(a).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM subjective_log WHERE athlete_id = ?").param(a).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        // a removed activity only empties the link (the golden replay does this inside a rolled-back transaction)
        jdbc.sql("DELETE FROM activity WHERE id = ?").param(act).update();
        assertThat(count("SELECT count(*) FROM subjective_log WHERE athlete_id = ? AND activity_id IS NULL", a)).isEqualTo(1);
        // deleting the athlete still removes everything (spec: delete per athlete)
        jdbc.sql("DELETE FROM athlete WHERE id = ?").param(a).update();
        assertThat(count("SELECT count(*) FROM subjective_log WHERE athlete_id = ?", a)).isZero();
    }

    @Test
    void theDatabaseRefusesAnIncoherentNiggle() {
        long a = newAthlete("Constraint");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO subjective_log (athlete_id, log_date, niggle_severity) VALUES (?, current_date, 3)""")
                .param(a).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO subjective_log (athlete_id, log_date, niggle_location, niggle_severity, niggle_resolved)
                VALUES (?, current_date, 'knee', 2, true)""").param(a).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNiggleThenItsResolutionBothKeptAndTheResolutionCounts() {
        long a = newAthlete("Niggle");
        var me = login(Role.ATHLETE, a);
        service.create(a, new CheckinRequest(null, null, null, null, null, "calf", 3, false, false, false, null), me);
        var done = service.create(a, new CheckinRequest(null, null, null, null, null, "calf", null, true, false, false, null), me);
        assertThat(done.niggleResolved()).isTrue();
        assertThat(service.recent(a, 3)).singleElement().extracting(CheckinView::niggleResolved).isEqualTo(true);
    }

    @Test
    void theTodayDateFollowsTheAthletesTimezone() {
        long a = newAthlete("Timezone");
        jdbc.sql("UPDATE athlete SET timezone = 'Pacific/Kiritimati' WHERE id = ?").param(a).update(); // UTC+14
        assertThat(service.today(a)).isEqualTo(LocalDate.now(clock.withZone(ZoneId.of("Pacific/Kiritimati"))));
    }

    @Test
    void athletesLogForThemselvesTheCoachSeesAllAndCanLogOnBehalf() throws Exception {
        long a = newAthlete("Access A");
        long b = newAthlete("Access B");
        var athleteA = login(Role.ATHLETE, a);
        var coach = login(Role.COACH, null);
        String body = "{\"rpe\":6,\"soreness\":2}";

        mvc.perform(post("/api/athletes/" + a + "/checkins").with(user(athleteA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.onBehalf").value(false));
        mvc.perform(post("/api/athletes/" + b + "/checkins").with(user(athleteA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/athletes/" + b + "/checkins/recent").with(user(athleteA))).andExpect(status().isForbidden());
        mvc.perform(get("/api/checkins/recent").with(user(athleteA))).andExpect(status().isForbidden());

        mvc.perform(post("/api/athletes/" + b + "/checkins").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.onBehalf").value(true));
        mvc.perform(get("/api/checkins/recent").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.athleteId==" + a + ")]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.athleteId==" + b + ")].onBehalf").value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void aBadCheckInIsA400() throws Exception {
        long a = newAthlete("Bad");
        var me = login(Role.ATHLETE, a);
        mvc.perform(post("/api/athletes/" + a + "/checkins").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rpe\":11}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/athletes/" + a + "/checkins").with(user(me)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM subjective_log WHERE athlete_id = ?", a)).isZero();
    }
}
