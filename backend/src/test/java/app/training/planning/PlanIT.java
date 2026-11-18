package app.training.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Stage 2-1a: entering a plan by hand, the history of every change, and who may do what. Synthetic rows only. */
@AutoConfigureMockMvc
class PlanIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;

    private CurrentUser login(Role role, Long athleteId) {
        long id = users.insert("u-plan-" + System.nanoTime(), "x", role, athleteId);
        return users.findById(id).orElseThrow();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private long createPlan(CurrentUser coach, long athlete, String start, String end) throws Exception {
        return body(mvc.perform(post("/api/athletes/" + athlete + "/plans").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Autumn block\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.origin").value("MANUAL"))).get("id").asLong();
    }

    private static final String TEMPO = """
            {"date":"2026-11-25","sessionType":"threshold","title":"Tempo","description":"3 km WU, 20 min @ T, 2 km CD",
             "targetDistanceKm":12.5,"targetDurationMin":60}""";

    @Test
    void enteringAndEditingASessionWritesItsHistory() throws Exception {
        long a = newAthlete("Plan A");
        CurrentUser coach = login(Role.COACH, null);
        long planId = createPlan(coach, a, "2026-11-23", "2026-11-29");

        long sid = body(mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(TEMPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.demand").value("KEY"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.targetDistanceKm").value(12.5))
                .andExpect(jsonPath("$.description").value("3 km WU, 20 min @ T, 2 km CD"))).get("id").asLong();
        assertThat(count("SELECT target_distance_m FROM planned_session WHERE id = ?", sid)).isEqualTo(12500);

        // An edit, then a status change: each is one EDIT row with before and after.
        mvc.perform(put("/api/sessions/" + sid).with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(TEMPO.replace("\"Tempo\"", "\"Tempo, shorter\"").replace("60", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Tempo, shorter"));
        mvc.perform(put("/api/sessions/" + sid).with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(TEMPO.replace("\"title\"", "\"status\":\"SKIPPED\",\"title\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"));

        JsonNode history = body(mvc.perform(get("/api/sessions/" + sid + "/revisions").with(user(login(Role.ATHLETE, a))))
                .andExpect(status().isOk()));
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("changeKind").asText()).isEqualTo("CREATE");
        assertThat(history.get(0).has("before")).isFalse();
        assertThat(history.get(0).get("after").get("title").asText()).isEqualTo("Tempo");
        assertThat(history.get(0).get("changedBy").asText()).isEqualTo(coach.username());
        assertThat(history.get(1).get("before").get("targetDurationMin").asInt()).isEqualTo(60);
        assertThat(history.get(1).get("after").get("targetDurationMin").asInt()).isEqualTo(50);
        assertThat(history.get(2).get("before").get("status").asText()).isEqualTo("PLANNED");
        assertThat(history.get(2).get("after").get("status").asText()).isEqualTo("SKIPPED");

        // A session outside the plan's dates stretches the plan (coach decision, ADR 0019 D2).
        mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-12-02\",\"sessionType\":\"easy\",\"title\":\"Easy 8\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.demand").value("EASY"));
        mvc.perform(get("/api/athletes/" + a + "/plans").with(user(coach)))
                .andExpect(jsonPath("$[0].endDate").value("2026-12-02"));

        mvc.perform(get("/api/athletes/" + a + "/sessions?from=2026-11-23&to=2026-12-06").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value("2026-11-25"));
    }

    @Test
    void historyRowsCannotBeChangedAndADeleteTakesTheHistoryWithIt() throws Exception {
        long a = newAthlete("Plan B");
        CurrentUser coach = login(Role.COACH, null);
        createPlan(coach, a, "2026-11-23", "2026-11-29");
        long sid = body(mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TEMPO))).get("id").asLong();

        assertThatThrownBy(() -> jdbc.sql("UPDATE planned_session_revision SET change_kind = 'EDIT' WHERE planned_session_id = ?")
                .param(sid).update()).hasMessageContaining("append-only");

        mvc.perform(delete("/api/sessions/" + sid).with(user(coach)).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM planned_session WHERE id = ?", sid)).isZero();
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE planned_session_id = ?", sid)).isZero();
    }

    @Test
    void badInputIsRefusedWithPlainMessagesAndNothingIsSaved() throws Exception {
        long a = newAthlete("Plan C");
        CurrentUser coach = login(Role.COACH, null);
        mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(TEMPO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_PLAN"));

        createPlan(coach, a, "2026-11-23", "2026-11-29");
        mvc.perform(post("/api/athletes/" + a + "/plans").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Second\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-31\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_PLAN_EXISTS"));
        mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-24\",\"sessionType\":\"fartlek\",\"estStrain\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown type 'fartlek'; Estimated strain must be between 0 and 21"));
        assertThat(count("SELECT count(*) FROM planned_session WHERE athlete_id = ?", a)).isZero();
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE athlete_id = ?", a)).isZero();
    }

    @Test
    void anAthleteReadsOnlyTheirOwnPlanAndCannotChangeIt() throws Exception {
        long a = newAthlete("Plan D");
        long other = newAthlete("Plan E");
        CurrentUser coach = login(Role.COACH, null);
        long planId = createPlan(coach, a, "2026-11-23", "2026-11-29");
        long sid = body(mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TEMPO))).get("id").asLong();

        CurrentUser athlete = login(Role.ATHLETE, a);
        mvc.perform(get("/api/athletes/" + a + "/sessions?from=2026-11-23&to=2026-11-29").with(user(athlete)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/athletes/" + a + "/plans").with(user(athlete))).andExpect(status().isOk());

        CurrentUser stranger = login(Role.ATHLETE, other);
        mvc.perform(get("/api/athletes/" + a + "/sessions?from=2026-11-23&to=2026-11-29").with(user(stranger)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/sessions/" + sid + "/revisions").with(user(stranger))).andExpect(status().isForbidden());

        mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(athlete)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TEMPO)).andExpect(status().isForbidden());
        mvc.perform(put("/api/sessions/" + sid).with(user(athlete)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TEMPO)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/sessions/" + sid).with(user(athlete)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/plans/" + planId).with(user(athlete)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"startDate\":\"2026-11-23\",\"endDate\":\"2026-11-29\"}"))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE planned_session_id = ?", sid)).isEqualTo(1);
    }

    @Test
    void aPlanCanBeArchivedAndItsDatesCannotDropASession() throws Exception {
        long a = newAthlete("Plan F");
        CurrentUser coach = login(Role.COACH, null);
        long planId = createPlan(coach, a, "2026-11-23", "2026-11-29");
        mvc.perform(post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TEMPO)).andExpect(status().isCreated());

        mvc.perform(put("/api/plans/" + planId).with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Autumn block\",\"startDate\":\"2026-11-26\",\"endDate\":\"2026-11-29\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/plans/" + planId).with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Autumn block\",\"startDate\":\"2026-11-23\",\"endDate\":\"2026-11-29\",\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        createPlan(coach, a, "2026-11-30", "2026-12-27"); // a new ACTIVE plan is allowed once the old one is archived
    }
}
