package app.training.adaptation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.adaptation.settings.SettingsService;
import app.training.adaptation.signals.SignalService;
import app.training.planning.PlanModels.PlanRequest;
import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.PlanService;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Suggestions stored against a real database (stage 2-3, ADR 0022; ADR 0018 D3-D4): every version on every row,
 * re-runs that keep, expire or replace pending suggestions, the duplicate guard, access, and the check-in hook.
 * Synthetic data only; the signals are written directly so each test controls them.
 */
@AutoConfigureMockMvc
class SuggestionIT extends IntegrationTest {
    @Autowired SuggestionService suggestions;
    @Autowired SuggestionRepository repo;
    @Autowired SignalService signals;
    @Autowired SettingsService settings;
    @Autowired PlanService plans;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;

    record Setup(long athlete, long coachId, LocalDate today, long hard, long easy) {}

    private CurrentUser login(Role role, Long athlete) {
        return users.findById(users.insert(role.name().toLowerCase() + "-sug-" + System.nanoTime(), "x", role, athlete)).orElseThrow();
    }

    private long session(long athlete, long coach, LocalDate date, String type, double km, int min) {
        return plans.createSession(athlete, new SessionRequest(null, date, type, null, null, "workout", BigDecimal.valueOf(km), min, null, null,
                null), coach).id();
    }

    /** A threshold session today and an easy run in two days (or tomorrow when today is Sunday, the week's end). */
    private Setup plan(String name) {
        long a = newAthlete(name);
        long coach = login(Role.COACH, null).id();
        LocalDate today = signals.today(a);
        plans.createPlan(a, new PlanRequest("Block", today.minusDays(30), today.plusDays(30), null), "MANUAL");
        long hard = session(a, coach, today, "threshold", 10, 60);
        long easy = session(a, coach, today.plusDays(1), "easy", 8, 48);
        return new Setup(a, coach, today, hard, easy);
    }

    private void signalsOn(long athlete, LocalDate day, String band, int recovery) {
        String json = """
                {"readiness": {"recoveryPct": %d, "band": "%s", "nonGreenStreakDays": %d, "beforeHrv": false},
                 "sleep": {"debtMin": 0, "nightsOfNeed": 0.0, "knownNights": 7},
                 "subjective": {"illness": false, "niggles": []},
                 "load": {"running": {"acwr": 1.0}},
                 "tolerance": {"kmPerWeek": 80.0, "source": "computed"}}""".formatted(recovery, band, "GREEN".equals(band) ? 0 : 1);
        long settingsId = settings.current().id();
        jdbc.sql("DELETE FROM day_signals WHERE athlete_id = ? AND cycle_date = ?").params(athlete, day).update();
        jdbc.sql("INSERT INTO day_signals (athlete_id, cycle_date, signals_version, settings_version, signals) VALUES (?, ?, ?, ?, ?::jsonb)")
                .params(athlete, day, SignalService.SIGNALS_VERSION, settingsId, json).update();
    }

    @Test
    void aRedDayStoresASwapWithEveryVersionTheInputsAndPlanShapedBeforeAndAfter() {
        var s = plan("Red day");
        signalsOn(s.athlete(), s.today(), "RED", 28);

        var o = suggestions.run(s.athlete());
        assertThat(o.created()).isEqualTo(1);
        var p = o.pending().get(0);
        assertThat(p.action()).isEqualTo("SWAP");
        assertThat(p.plannedSessionId()).isEqualTo(s.hard());
        assertThat(p.counterpartSessionId()).isEqualTo(s.easy());
        assertThat(p.forDate()).isEqualTo(s.today());
        assertThat(p.rulesVersion()).isEqualTo(RulesEngine.RULES_VERSION);
        assertThat(p.settingsVersion()).isEqualTo(settings.current().id());
        assertThat(p.signalsVersion()).isEqualTo(SignalService.SIGNALS_VERSION);
        assertThat(p.engineRunDate()).isEqualTo(s.today());
        assertThat(p.confidence()).isEqualTo("HIGH");
        assertThat(p.reasons()).hasSize(2);
        assertThat(p.reasons().get(0).path("text").asText()).isEqualTo("Recovery is red today (28%).");
        assertThat(p.reasons().get(0).path("code").asText()).isEqualTo("red.recovery");
        assertThat(p.inputsSnapshot().path("rule").asText()).isEqualTo("red");
        assertThat(p.inputsSnapshot().path("today").path("recoveryPct").asInt()).isEqualTo(28);
        assertThat(p.before().path("session").path("id").asLong()).isEqualTo(s.hard());
        assertThat(p.after().path("session").path("date").asText()).isEqualTo(s.today().plusDays(1).toString());
        assertThat(p.after().path("session").path("sessionType").asText()).isEqualTo("threshold");
        assertThat(p.after().path("counterpart").path("date").asText()).isEqualTo(s.today().toString());
        assertThat(count("SELECT count(*) FROM adaptation_suggestion WHERE athlete_id = ? AND dedup_key IS NOT NULL", s.athlete())).isEqualTo(1);
    }

    @Test
    void aRerunWithTheSameInputsKeepsThePendingSuggestion() {
        var s = plan("Same inputs");
        signalsOn(s.athlete(), s.today(), "RED", 28);
        long first = suggestions.run(s.athlete()).pending().get(0).id();

        var o = suggestions.run(s.athlete());
        assertThat(o.created()).isZero();
        assertThat(o.kept()).isEqualTo(1);
        assertThat(o.pending()).singleElement().extracting(SuggestionRepository.Suggestion::id).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM adaptation_suggestion WHERE athlete_id = ?", s.athlete())).isEqualTo(1);
    }

    @Test
    void aRerunWithFresherInputsExpiresTheOutdatedSuggestionThenStoresTheNewOne() {
        var s = plan("Fresher inputs");
        signalsOn(s.athlete(), s.today(), "RED", 28);
        long first = suggestions.run(s.athlete()).pending().get(0).id();

        signalsOn(s.athlete(), s.today(), "RED", 21); // a later sync changed this morning's score
        var o = suggestions.run(s.athlete());
        assertThat(o.created()).isEqualTo(1);
        assertThat(o.expired()).isEqualTo(1);
        var fresh = o.pending().get(0);
        assertThat(fresh.id()).isNotEqualTo(first);
        assertThat(fresh.reasons().get(0).path("text").asText()).isEqualTo("Recovery is red today (21%).");
        assertThat(jdbc.sql("SELECT status FROM adaptation_suggestion WHERE id = ?").param(first).query(String.class).single()).isEqualTo("EXPIRED");
    }

    @Test
    void aSuggestionTheEngineNoLongerMakesIsExpired() {
        var s = plan("No longer");
        signalsOn(s.athlete(), s.today(), "RED", 28);
        suggestions.run(s.athlete());

        signalsOn(s.athlete(), s.today(), "GREEN", 70);
        var o = suggestions.run(s.athlete());
        assertThat(o.expired()).isEqualTo(1);
        assertThat(o.pending()).isEmpty();
    }

    @Test
    void theDatabaseRefusesASecondPendingCopy() {
        var s = plan("Duplicate");
        signalsOn(s.athlete(), s.today(), "RED", 28);
        var p = suggestions.run(s.athlete()).pending().get(0);
        var copy = new SuggestionRepository.NewSuggestion(s.athlete(), p.plannedSessionId(), p.counterpartSessionId(), p.forDate(), p.action(),
                p.before(), p.after(), p.reasons(), p.inputsSnapshot(), p.confidence(), p.rulesVersion(), null, p.settingsVersion(),
                p.signalsVersion(), p.engineRunDate());
        assertThatThrownBy(() -> repo.insert(copy)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void aSuggestionRejectedTodayIsNotMadeAgainUntilTomorrow() {
        var s = plan("Rejected");
        signalsOn(s.athlete(), s.today(), "RED", 28);
        long id = suggestions.run(s.athlete()).pending().get(0).id();
        jdbc.sql("UPDATE adaptation_suggestion SET status = 'REJECTED', decided_at = now() WHERE id = ?").param(id).update();

        var o = suggestions.run(s.athlete());
        assertThat(o.created()).isZero();
        assertThat(o.pending()).isEmpty();
    }

    @Test
    void anAthleteReadsOnlyTheirOwnSuggestionsAndOnlyTheCoachRunsTheEngine() throws Exception {
        var s = plan("Access");
        long other = newAthlete("Access other");
        signalsOn(s.athlete(), s.today(), "RED", 28);
        var coach = login(Role.COACH, null);
        var athlete = login(Role.ATHLETE, s.athlete());

        mvc.perform(post("/api/athletes/{id}/suggestions/run", s.athlete()).with(user(athlete)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/athletes/{id}/suggestions/run", s.athlete()).with(user(coach)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rulesVersion").value(RulesEngine.RULES_VERSION))
                .andExpect(jsonPath("$.engineRunDate").value(s.today().toString()));
        mvc.perform(get("/api/athletes/{id}/suggestions", s.athlete()).with(user(athlete))).andExpect(status().isOk());
        mvc.perform(get("/api/athletes/{id}/suggestions", other).with(user(athlete))).andExpect(status().isForbidden());
        mvc.perform(get("/api/athletes/{id}/suggestions?status=nonsense", s.athlete()).with(user(coach))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/athletes/{id}/suggestions/run", 999_999L).with(user(coach)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void anIllnessCheckInUpdatesTheSuggestionsAtOnce() throws Exception {
        long a = newAthlete("Illness check-in");
        long coach = login(Role.COACH, null).id();
        LocalDate today = signals.today(a);
        for (int i = 0; i <= 10; i++) {
            jdbc.sql("""
                    INSERT INTO day_features (athlete_id, cycle_date, feature_version, day_load_edwards, day_load_banister, run_km, has_garmin_core)
                    VALUES (?, ?, 'f3', 50, 40, 0, true)""").params(a, today.minusDays(i)).update();
        }
        plans.createPlan(a, new PlanRequest("Block", today.minusDays(7), today.plusDays(14), null), "MANUAL");
        long easy = session(a, coach, today, "easy", 8, 48);
        var athlete = login(Role.ATHLETE, a);

        mvc.perform(post("/api/athletes/{id}/checkins", a).with(user(athlete)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"illness\": true}"))
                .andExpect(status().isCreated());

        var pending = repo.pending(a);
        assertThat(pending).singleElement().satisfies(p -> {
            assertThat(p.plannedSessionId()).isEqualTo(easy);
            assertThat(p.action()).isEqualTo("REPLACE");
            assertThat(p.after().path("session").path("sessionType").asText()).isEqualTo("rest");
            assertThat(p.reasons().get(0).path("text").asText()).isEqualTo("Illness was reported today: rest or very easy until symptoms clear.");
        });
    }
}
