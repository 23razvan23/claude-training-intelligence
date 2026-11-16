package app.training.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import app.training.IntegrationTest;
import app.training.ingestion.Fixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SecurityIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    long athleteA;
    long athleteB;
    CurrentUser coach;
    CurrentUser athleteUserA;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        athleteA = newAthlete("A " + suffix);
        athleteB = newAthlete("B " + suffix);
        long coachId = users.insert("coach" + suffix, encoder.encode("coach-password-1"), Role.COACH, null);
        long aId = users.insert("athlete" + suffix, encoder.encode("athlete-password-1"), Role.ATHLETE, athleteA);
        coach = users.findById(coachId).orElseThrow();
        athleteUserA = users.findById(aId).orElseThrow();
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/athletes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void apiDescriptionPagesNeedALogin() throws Exception {
        mvc.perform(get("/api/openapi")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/openapi").with(user(athleteUserA))).andExpect(status().isOk());
    }

    @Test
    void athleteSeesOnlyThemselves() throws Exception {
        mvc.perform(get("/api/athletes").with(user(athleteUserA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(athleteA));
        mvc.perform(get("/api/athletes/" + athleteA).with(user(athleteUserA))).andExpect(status().isOk());
        mvc.perform(get("/api/athletes/" + athleteB).with(user(athleteUserA))).andExpect(status().isForbidden());
        mvc.perform(get("/api/athletes/" + athleteB + "/coverage").with(user(athleteUserA))).andExpect(status().isForbidden());
        mvc.perform(get("/api/athletes/" + athleteA + "/coverage").with(user(athleteUserA))).andExpect(status().isOk());
    }

    @Test
    void athleteCannotWrite() throws Exception {
        mvc.perform(post("/api/athletes").with(user(athleteUserA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/athletes/" + athleteA + "/imports/whoop")
                        .file(new MockMultipartFile("files", "a.csv", "text/csv", "x".getBytes()))
                        .with(user(athleteUserA)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users").with(user(athleteUserA))).andExpect(status().isForbidden());
    }

    @Test
    void coachManagesAthletesAndLogins() throws Exception {
        var res = mvc.perform(post("/api/athletes").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New Runner","sex":"F","hrMax":190,"restingHr":45,"lthr":172,
                                 "thresholdPaceSPerKm":250,"hrZones":[{"zone":1,"min":100,"max":135}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timezone").value("Europe/Bucharest"))
                .andExpect(jsonPath("$.hrZones[0].max").value(135))
                .andReturn();
        long id = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(put("/api/athletes/" + id).with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Runner\",\"timezone\":\"Not/AZone\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/athletes").with(user(coach)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"hrMax\":400}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/users").with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"runner%d\",\"password\":\"long-enough-pw\",\"athleteId\":%d}".formatted(id, id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ATHLETE"));
        // one login per athlete
        mvc.perform(post("/api/users").with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"other%d\",\"password\":\"long-enough-pw\",\"athleteId\":%d}".formatted(id, id)))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/athletes/" + id).with(user(coach)).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM app_user WHERE athlete_id = ?", id)).isZero();
    }

    @Test
    void uploadRunsInBackgroundAndCanBePolled(@TempDir Path dir) throws Exception {
        Fixtures.whoopCsvs(dir, LocalDate.of(2024, 5, 1), 4);
        var res = mvc.perform(multipart("/api/athletes/" + athleteA + "/imports/whoop")
                        .file(new MockMultipartFile("files", "physiological_cycles.csv", "text/csv",
                                Files.readAllBytes(dir.resolve("physiological_cycles.csv"))))
                        .with(user(coach)).with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn();
        long batchId = json.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        String status = "PENDING";
        for (int i = 0; i < 100 && (status.equals("PENDING") || status.equals("RUNNING")); i++) {
            Thread.sleep(100);
            var poll = mvc.perform(get("/api/imports/" + batchId).with(user(athleteUserA))).andReturn();
            status = json.readTree(poll.getResponse().getContentAsString()).get("status").asText();
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT count(*) FROM whoop_reference WHERE athlete_id = ?", athleteA)).isEqualTo(4);
        // Athlete B's login could not read it
        long bUser = users.insert("b" + System.nanoTime(), "x", Role.ATHLETE, athleteB);
        mvc.perform(get("/api/imports/" + batchId).with(user(users.findById(bUser).orElseThrow())))
                .andExpect(status().isForbidden());
    }
}
