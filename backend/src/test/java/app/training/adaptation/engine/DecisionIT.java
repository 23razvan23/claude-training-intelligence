package app.training.adaptation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Accept, edit and reject against a real database (stage 2-3 PR 2, ADR 0022; ADR 0018 D5): accepting changes the plan
 * through PlanService, links every changed session to the suggestion and writes history rows naming it; every
 * decision is logged. Synthetic data only.
 */
@AutoConfigureMockMvc
class DecisionIT extends IntegrationTest {
    @Autowired SuggestionService suggestions;
    @Autowired SignalService signals;
    @Autowired SettingsService settings;
    @Autowired PlanService plans;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    private CurrentUser login(Role role, Long athlete) {
        return users.findById(users.insert(role.name().toLowerCase() + "-dec-" + System.nanoTime(), "x", role, athlete)).orElseThrow();
    }

    private long session(long athlete, long coach, LocalDate date, String type, double km, int min) {
        return plans.createSession(athlete, new SessionRequest(null, date, type, null, null, "workout", BigDecimal.valueOf(km), min, null, null,
                null, null), coach).id();
    }

    private void red(long athlete, LocalDate day) {
        String sig = """
                {"readiness": {"recoveryPct": 25, "band": "RED", "nonGreenStreakDays": 1, "beforeHrv": false},
                 "sleep": {"debtMin": 0, "nightsOfNeed": 0.0, "knownNights": 7},
                 "subjective": {"illness": false, "niggles": [{"location": "calf", "since": "%s", "persistDays": 4}]},
                 "load": {"running": {"acwr": 1.0}}, "tolerance": {"kmPerWeek": 80.0, "source": "computed"}}""".formatted(day.minusDays(3));
        jdbc.sql("INSERT INTO day_signals (athlete_id, cycle_date, signals_version, settings_version, signals) VALUES (?, ?, ?, ?, ?::jsonb)")
                .params(athlete, day, SignalService.SIGNALS_VERSION, settings.current().id(), sig).update();
    }

    record World(long athlete, CurrentUser coach, LocalDate today) {}

    private World world(String name) {
        long a = newAthlete(name);
        var coach = login(Role.COACH, null);
        LocalDate today = signals.today(a);
        plans.createPlan(a, new PlanRequest("Block", today.minusDays(30), today.plusDays(30), null), "MANUAL");
        red(a, today);
        return new World(a, coach, today);
    }

    private long revisionsWith(long suggestionId) {
        return count("SELECT count(*) FROM planned_session_revision WHERE suggestion_id = ?", suggestionId);
    }

    @Test
    void acceptingASwapMovesBothSessionsLinksThemAndWritesTheirHistory() throws Exception {
        var w = world("Accept swap");
        long hard = session(w.athlete(), w.coach().id(), w.today(), "long", 18, 110);
        long easy = session(w.athlete(), w.coach().id(), w.today().plusDays(1), "easy", 8, 48);
        // A niggle is open but a long run is not intensity; the red rule swaps.
        var s = suggestions.run(w.athlete()).pending().get(0);
        assertThat(s.action()).isEqualTo("SWAP");

        mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(w.coach())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.changed.length()").value(2));

        assertThat(plans.session(hard).date()).isEqualTo(w.today().plusDays(1));
        assertThat(plans.session(easy).date()).isEqualTo(w.today());
        assertThat(plans.session(hard).changedBySuggestionId()).isEqualTo(s.id());
        assertThat(plans.session(easy).changedBySuggestionId()).isEqualTo(s.id());
        assertThat(revisionsWith(s.id())).isEqualTo(2);
        var decided = suggestions.forSession(hard).get(0);
        assertThat(decided.decidedBy()).isEqualTo(w.coach().username());
        assertThat(decided.decidedAt()).isNotNull();
    }

    @Test
    void acceptingAReplacementAddsANewSessionAndMarksTheOldOneReplaced() throws Exception {
        var w = world("Accept replace");
        long hills = session(w.athlete(), w.coach().id(), w.today(), "hills", 10, 60);
        var s = suggestions.run(w.athlete()).pending().get(0);
        assertThat(s.action()).isEqualTo("REPLACE");

        String body = mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(w.coach())).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long created = json.readTree(body).path("changed").get(0).path("id").asLong();

        var old = plans.session(hills);
        var replacement = plans.session(created);
        assertThat(old.status().name()).isEqualTo("REPLACED");
        assertThat(old.changedBySuggestionId()).isEqualTo(s.id());
        assertThat(replacement.replacesSessionId()).isEqualTo(hills);
        assertThat(replacement.changedBySuggestionId()).isEqualTo(s.id());
        assertThat(replacement.sessionType()).isEqualTo("easy");
        assertThat(replacement.planId()).isEqualTo(old.planId());
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE suggestion_id = ? AND change_kind = 'CREATE'", s.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE suggestion_id = ? AND change_kind = 'EDIT'", s.id())).isEqualTo(1);
    }

    @Test
    void editingThenAcceptingAppliesTheCoachsVersionAndIsLoggedAsEdited() throws Exception {
        var w = world("Edit");
        long hills = session(w.athlete(), w.coach().id(), w.today(), "hills", 10, 60);
        var s = suggestions.run(w.athlete()).pending().get(0);
        ObjectNode after = s.after().deepCopy();
        ((ObjectNode) after.get("session")).put("targetDistanceKm", 5).put("targetDurationMin", 30);
        String body = json.writeValueAsString(json.createObjectNode().<ObjectNode>set("after", after).put("note", "Short and easy"));

        mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(w.coach())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.status").value("EDITED"))
                .andExpect(jsonPath("$.suggestion.coachNote").value("Short and easy"))
                .andExpect(jsonPath("$.changed[0].targetDurationMin").value(30));
        assertThat(plans.session(hills).status().name()).isEqualTo("REPLACED");
    }

    @Test
    void sendingBackTheSuggestedVersionUnchangedCountsAsAccepted() throws Exception {
        var w = world("Unchanged");
        session(w.athlete(), w.coach().id(), w.today(), "hills", 10, 60);
        var s = suggestions.run(w.athlete()).pending().get(0);
        String body = json.writeValueAsString(json.createObjectNode().set("after", s.after()));
        mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(w.coach())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.status").value("ACCEPTED"));
    }

    @Test
    void rejectingLogsTheDecisionAndLeavesThePlanAlone() throws Exception {
        var w = world("Reject");
        long hills = session(w.athlete(), w.coach().id(), w.today(), "hills", 10, 60);
        var s = suggestions.run(w.athlete()).pending().get(0);

        mvc.perform(post("/api/suggestions/{id}/reject", s.id()).with(user(w.coach())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\": \"Feels fine, race-specific\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion.status").value("REJECTED"))
                .andExpect(jsonPath("$.suggestion.coachNote").value("Feels fine, race-specific"));
        assertThat(plans.session(hills).status().name()).isEqualTo("PLANNED");
        assertThat(plans.session(hills).changedBySuggestionId()).isNull();
        assertThat(revisionsWith(s.id())).isZero();

        mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(w.coach())).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUGGESTION_DECIDED"));
    }

    @Test
    void aSuggestionAboutASessionEditedSinceIsRefusedAsOutdated() throws Exception {
        var w = world("Outdated");
        long hills = session(w.athlete(), w.coach().id(), w.today(), "hills", 10, 60);
        var s = suggestions.run(w.athlete()).pending().get(0);
        Thread.sleep(5); // updated_at must move
        plans.updateSession(hills, new SessionRequest(null, w.today(), "hills", null, "Hill repeats", "6x90s", BigDecimal.TEN, 60, null, null,
                null, null), w.coach().id());

        mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(w.coach())).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUGGESTION_OUTDATED"));
        assertThat(jdbc.sql("SELECT status FROM adaptation_suggestion WHERE id = ?").param(s.id()).query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void aBadEditIsRefusedAndNothingChanges() throws Exception {
        var w = world("Bad edit");
        long hills = session(w.athlete(), w.coach().id(), w.today(), "hills", 10, 60);
        var s = suggestions.run(w.athlete()).pending().get(0);
        ObjectNode after = s.after().deepCopy();
        ((ObjectNode) after.get("session")).put("sessionType", "tempo");
        mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(w.coach())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(json.createObjectNode().set("after", after))))
                .andExpect(status().isBadRequest());
        assertThat(plans.session(hills).status().name()).isEqualTo("PLANNED");
        assertThat(count("SELECT count(*) FROM planned_session WHERE athlete_id = ?", w.athlete())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM adaptation_suggestion WHERE id = ?").param(s.id()).query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void onlyTheCoachDecidesAndAthletesReadTheirOwnTodayCardAndSessionHistory() throws Exception {
        var w = world("Access");
        long hills = session(w.athlete(), w.coach().id(), w.today(), "hills", 10, 60);
        var s = suggestions.run(w.athlete()).pending().get(0);
        var athlete = login(Role.ATHLETE, w.athlete());
        var stranger = login(Role.ATHLETE, newAthlete("Other"));

        mvc.perform(post("/api/suggestions/{id}/accept", s.id()).with(user(athlete)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/suggestions/{id}/reject", s.id()).with(user(athlete)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/athletes/{id}/suggestions/today", w.athlete()).with(user(athlete)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(w.today().toString()))
                .andExpect(jsonPath("$.pending.length()").value(1))
                .andExpect(jsonPath("$.healthMessages[0].code").value("health.niggle"));
        mvc.perform(get("/api/sessions/{id}/suggestions", hills).with(user(athlete))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(s.id()));
        mvc.perform(get("/api/sessions/{id}/suggestions", hills).with(user(stranger))).andExpect(status().isForbidden());
        mvc.perform(post("/api/suggestions/{id}/accept", 999_999L).with(user(w.coach())).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void theTaperFlagIsSavedKeptOnAnEditAndProtectsTheSessionFromSwaps() throws Exception {
        var w = world("Taper");
        String create = """
                {"date": "%s", "sessionType": "threshold", "targetDistanceKm": 8, "targetDurationMin": 45, "taper": true}""".formatted(w.today());
        String body = mvc.perform(post("/api/athletes/{id}/sessions", w.athlete()).with(user(w.coach())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.taper").value(true)).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).path("id").asLong();
        session(w.athlete(), w.coach().id(), w.today().plusDays(1), "easy", 6, 36);
        String edit = """
                {"date": "%s", "sessionType": "threshold", "targetDistanceKm": 7, "targetDurationMin": 40}""".formatted(w.today());
        mvc.perform(put("/api/sessions/{id}", id).with(user(w.coach())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taper").value(true));

        var s = suggestions.run(w.athlete()).pending().get(0);
        assertThat(s.action()).isEqualTo("REPLACE"); // a red day in the taper: an easy run, not a swap
        assertThat(s.reasons().get(1).path("code").asText()).isEqualTo("guard.raceWeek");
    }
}
