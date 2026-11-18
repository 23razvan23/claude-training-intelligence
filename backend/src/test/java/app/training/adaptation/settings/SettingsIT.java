package app.training.adaptation.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.planning.PlanModels.PlanRequest;
import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.PlanService;
import app.training.planning.SessionTypes.Demand;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The settings store against a real database (stage 2-2, ADR 0021). The database is shared, so tests never leave changed values. */
@AutoConfigureMockMvc
class SettingsIT extends IntegrationTest {
    @Autowired SettingsService settings;
    @Autowired SettingsRepository repo;
    @Autowired PlanService plans;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    @Test
    void theDefaultsAreSavedOnceAndReadBack() {
        var first = settings.current();
        long rows = count("SELECT count(*) FROM settings_version");
        var again = settings.current();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(count("SELECT count(*) FROM settings_version")).as("no new version without a change").isEqualTo(rows);
        for (var s : SettingsCatalog.all()) assertThat(SettingsCatalog.at(again.settings(), s.path())).as(s.path()).isNotNull();
        assertThat(again.intAt("signals.load.acuteSpanDays")).isEqualTo(7);
    }

    @Test
    void aValueAddedByAnUpdateBecomesANewVersionAndOlderVersionsStayAsTheyWere() {
        var base = settings.current();
        ObjectNode older = base.settings().deepCopy();
        ((ObjectNode) older.get("signals").get("foster")).remove("minKnownDays");
        older.put("testMarker", "kept");
        long olderId = repo.insert(older, "test: a version from before minKnownDays existed", null);

        var now = settings.current();
        assertThat(now.id()).isGreaterThan(olderId);
        assertThat(now.note()).contains("signals.foster.minKnownDays");
        assertThat(now.intAt("signals.foster.minKnownDays")).isEqualTo(5);
        assertThat(now.settings().get("testMarker").asText()).as("stored values are kept").isEqualTo("kept");
        assertThat(SettingsCatalog.at(repo.find(olderId).orElseThrow().settings(), "signals.foster.minKnownDays"))
                .as("the older version is never changed").isNull();
    }

    @Test
    void savedVersionsCanNotBeChangedOrDeleted() {
        long id = settings.current().id();
        assertThatThrownBy(() -> jdbc.sql("UPDATE settings_version SET note = 'x' WHERE id = ?").param(id).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM settings_version WHERE id = ?").param(id).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void aNewSessionTakesItsDemandFromTheCurrentSettings() {
        long a = newAthlete("Demand from settings");
        long coach = users.insert("coach-settings-" + System.nanoTime(), "x", Role.COACH, null);
        var d = LocalDate.of(2026, 12, 1);
        plans.createPlan(a, new PlanRequest("Winter", d, d.plusDays(30), null), "MANUAL");
        ObjectNode changed = settings.current().settings().deepCopy();
        ((ObjectNode) changed.get("planning").get("defaultDemand")).put("hills", "KEY");
        repo.insert(changed, "test: hills start as KEY", null);
        try {
            var s = plans.createSession(a, new SessionRequest(null, d, "hills", null, null, null, null, null, null, null, null), coach);
            assertThat(s.demand()).isEqualTo(Demand.KEY);
        } finally {
            ObjectNode back = settings.current().settings().deepCopy();
            ((ObjectNode) back.get("planning").get("defaultDemand")).put("hills", "SUPPORTING");
            repo.insert(back, "test: back to the default", null);
        }
        var s = plans.createSession(a, new SessionRequest(null, d, "hills", null, null, null, null, null, null, null, null), coach);
        assertThat(s.demand()).isEqualTo(Demand.SUPPORTING);
    }

    @Test
    void theCoachCanReadTheSettingsAnAthleteCanNot() throws Exception {
        var current = settings.current();
        mvc.perform(get("/api/settings").with(user(coachUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(current.id()))
                .andExpect(jsonPath("$.values[0].path").value("signals.load.dailyLoad"))
                .andExpect(jsonPath("$.values[0].rationale").isNotEmpty());
        long a = newAthlete("Settings athlete");
        mvc.perform(get("/api/settings").with(user(users.findById(users.insert("ath-settings-" + System.nanoTime(), "x", Role.ATHLETE, a))
                        .orElseThrow()))).andExpect(status().isForbidden());
    }

    private app.training.security.CurrentUser coachUser() {
        return users.findById(users.insert("coach-read-" + System.nanoTime(), "x", Role.COACH, null)).orElseThrow();
    }
}
