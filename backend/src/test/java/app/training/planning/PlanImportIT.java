package app.training.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Stage 2-1a PR 2: spreadsheet import, preview first and nothing saved until Import. Synthetic rows only. */
@AutoConfigureMockMvc
class PlanImportIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;

    static final String HEADER = "date,type,demand,title,workout,distance_km,duration_min,notes\n";

    CurrentUser login(Role role, Long athleteId) {
        long id = users.insert("u-imp-" + System.nanoTime(), "x", role, athleteId);
        return users.findById(id).orElseThrow();
    }

    ResultActions send(String step, long athlete, CurrentUser who, byte[] file, String name) throws Exception {
        return mvc.perform(multipart("/api/athletes/" + athlete + "/plan-import/" + step)
                .file(new MockMultipartFile("file", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, file))
                .with(user(who)).with(csrf()));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    long plan(CurrentUser coach, long athlete, String start, String end) throws Exception {
        return body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/athletes/" + athlete + "/plans").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Import block\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    static byte[] csv(String rows) {
        return (HEADER + rows).getBytes(StandardCharsets.UTF_8);
    }

    static final String GOOD = """
            25/11/2030,threshold,,Tempo,"3 km WU, 20 min @ T",12.5,60,
            26/11/2030,rest,,,,,,
            27/11/2030,long,supporting,,Steady,24,150,Flat course
            """;

    @Test
    void previewListsEveryRowAndSavesNothing() throws Exception {
        long a = newAthlete("Imp A");
        CurrentUser coach = login(Role.COACH, null);
        long planId = plan(coach, a, "2030-11-25", "2030-11-30");

        send("preview", a, coach, csv(GOOD), "plan.csv").andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(true))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.errorCount").value(0))
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.rows[0].row").value(2))
                .andExpect(jsonPath("$.rows[0].workout").value("3 km WU, 20 min @ T"))
                .andExpect(jsonPath("$.rows[2].demand").value("SUPPORTING"));
        assertThat(count("SELECT count(*) FROM planned_session WHERE athlete_id = ?", a)).isZero();
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE athlete_id = ?", a)).isZero();
    }

    @Test
    void commitSavesEveryRowThroughTheSameRulesAsTypingThem() throws Exception {
        long a = newAthlete("Imp B");
        CurrentUser coach = login(Role.COACH, null);
        long planId = plan(coach, a, "2030-11-25", "2030-11-26");

        // 27/11 is outside the plan's dates, so the plan stretches (ADR 0019 D2), as for a typed session.
        send("commit", a, coach, csv(GOOD), "plan.csv").andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.stretchedPlan").value(true));

        assertThat(count("SELECT count(*) FROM planned_session WHERE plan_id = ?", planId)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM planned_session_revision r JOIN planned_session s ON s.id = r.planned_session_id "
                + "WHERE s.plan_id = ? AND r.change_kind = 'CREATE'", planId)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM plan WHERE id = ? AND end_date = DATE '2030-11-27'", planId)).isEqualTo(1);
        // The type's default demand fills an empty demand, the coach's own demand is kept, the workout is the one text step.
        assertThat(jdbc.sql("SELECT demand FROM planned_session WHERE plan_id = ? AND session_type = 'threshold'")
                .param(planId).query(String.class).single()).isEqualTo("KEY");
        assertThat(jdbc.sql("SELECT demand FROM planned_session WHERE plan_id = ? AND session_type = 'long'")
                .param(planId).query(String.class).single()).isEqualTo("SUPPORTING");
        assertThat(jdbc.sql("SELECT structure -> 0 ->> 'text' FROM planned_session WHERE plan_id = ? AND session_type = 'threshold'")
                .param(planId).query(String.class).single()).isEqualTo("3 km WU, 20 min @ T");
        assertThat(count("SELECT target_distance_m FROM planned_session WHERE plan_id = ? AND session_type = 'long'", planId))
                .isEqualTo(24000);
    }

    @Test
    void anXlsxImportsLikeTheCsv() throws Exception {
        long a = newAthlete("Imp C");
        CurrentUser coach = login(Role.COACH, null);
        long planId = plan(coach, a, "2030-11-25", "2030-11-30");
        byte[] xlsx = XlsxFixture.build(List.of(Arrays.asList(PlanImportParser.COLUMNS.toArray()),
                Arrays.asList("25/11/2030", "easy", null, null, "Relaxed", 8.5, 45, null)));
        send("commit", a, coach, xlsx, "plan.xlsx").andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(1));
        assertThat(count("SELECT target_distance_m FROM planned_session WHERE plan_id = ?", planId)).isEqualTo(8500);
    }

    @Test
    void errorsAreShownPerRowAndImportIsRefusedWithNothingSaved() throws Exception {
        long a = newAthlete("Imp D");
        CurrentUser coach = login(Role.COACH, null);
        plan(coach, a, "2030-11-25", "2030-11-30");
        byte[] bad = csv("""
                25/11/2030,easy,,,,8,45,
                26/11/2030,tempo,,,,,,
                27/11/2030,easy,,,,12,45.5,
                """);
        send("preview", a, coach, bad, "plan.csv").andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(false))
                .andExpect(jsonPath("$.errorCount").value(2))
                .andExpect(jsonPath("$.rows[0].errors.length()").value(0))
                .andExpect(jsonPath("$.rows[1].errors[0]").value("row 3: unknown type 'tempo'"))
                .andExpect(jsonPath("$.rows[2].errors[0]").value("row 4: duration_min '45.5' must be a whole number of minutes, like 60"));
        send("commit", a, coach, bad, "plan.csv").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("row 3: unknown type 'tempo'")));
        // The good first row was not saved either: all or nothing.
        assertThat(count("SELECT count(*) FROM planned_session WHERE athlete_id = ?", a)).isZero();
    }

    @Test
    void warnsAboutSessionsOnTheSameDateAndImportsThemAlongside() throws Exception {
        long a = newAthlete("Imp E");
        CurrentUser coach = login(Role.COACH, null);
        long planId = plan(coach, a, "2030-11-25", "2030-11-30");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/athletes/" + a + "/sessions").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2030-11-25\",\"sessionType\":\"easy\"}")).andExpect(status().isCreated());
        byte[] file = csv("""
                25/11/2030,threshold,,,,,,
                25/11/2030,strides,,,,,,
                01/01/2020,easy,,,,,,
                05/12/2030,easy,,,,,,
                """);
        send("preview", a, coach, file, "plan.csv").andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(true))
                .andExpect(jsonPath("$.warningCount").value(4))
                .andExpect(jsonPath("$.rows[0].warnings[0]").value("this date already has a session (easy); the new one is added next to it"))
                .andExpect(jsonPath("$.rows[0].warnings[1]").value("another row in this file has the same date"))
                .andExpect(jsonPath("$.rows[2].warnings[0]").value("this date is in the past"))
                .andExpect(jsonPath("$.rows[3].warnings[0]").value(org.hamcrest.Matchers.containsString("plan will be stretched")))
                .andExpect(jsonPath("$.newPlanStart").value("2020-01-01"))
                .andExpect(jsonPath("$.newPlanEnd").value("2030-12-05"));
        send("commit", a, coach, file, "plan.csv").andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(4));
        assertThat(count("SELECT count(*) FROM planned_session WHERE plan_id = ? AND session_date = DATE '2030-11-25'", planId)).isEqualTo(3);
    }

    @Test
    void withoutAnActivePlanThePreviewSaysSoAndCommitIsRefused() throws Exception {
        long a = newAthlete("Imp F");
        CurrentUser coach = login(Role.COACH, null);
        send("preview", a, coach, csv(GOOD), "plan.csv").andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(false))
                .andExpect(jsonPath("$.fileErrors[0]").value("This athlete has no active plan. Create a plan first, then import."));
        send("commit", a, coach, csv(GOOD), "plan.csv").andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM planned_session WHERE athlete_id = ?", a)).isZero();
    }

    @Test
    void aWrongFileIsExplainedAtFileLevel() throws Exception {
        long a = newAthlete("Imp G");
        CurrentUser coach = login(Role.COACH, null);
        plan(coach, a, "2030-11-25", "2030-11-30");
        send("preview", a, coach, "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), "x.csv").andExpect(status().isOk())
                .andExpect(jsonPath("$.canImport").value(false))
                .andExpect(jsonPath("$.fileErrors[0]").value(org.hamcrest.Matchers.startsWith("Unknown column 'a'")));
    }

    @Test
    void onlyTheCoachMayImport() throws Exception {
        long a = newAthlete("Imp H");
        plan(login(Role.COACH, null), a, "2030-11-25", "2030-11-30");
        CurrentUser athlete = login(Role.ATHLETE, a);
        send("preview", a, athlete, csv(GOOD), "plan.csv").andExpect(status().isForbidden());
        send("commit", a, athlete, csv(GOOD), "plan.csv").andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM planned_session WHERE athlete_id = ?", a)).isZero();
    }
}
