package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.ingestion.Fixtures;
import app.training.ingestion.ImportBatchRepository;
import app.training.ingestion.ImportKind;
import app.training.ingestion.ImportRunner;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MetricsIT extends IntegrationTest {
    static final LocalDate FIRST = LocalDate.of(2026, 2, 1);
    static final int DAYS = 40;

    @Autowired MockMvc mvc;
    @Autowired ImportRunner runner;
    @Autowired ImportBatchRepository batches;
    @Autowired UserRepository users;
    @TempDir Path dir;

    CurrentUser coach;

    @BeforeEach
    void coach() {
        long id = users.insert("coach-m" + System.nanoTime(), "x", Role.COACH, null);
        coach = users.findById(id).orElseThrow();
    }

    private void importAll(long athlete) throws Exception {
        Fixtures.whoopCsvs(dir, FIRST, DAYS);
        Path zip = Fixtures.garminExport(dir, FIRST, DAYS);
        run(athlete, ImportKind.WHOOP_EXPORT, List.of(dir.resolve("physiological_cycles.csv")));
        run(athlete, ImportKind.GARMIN_EXPORT, List.of(zip));
    }

    private void run(long athlete, ImportKind kind, List<Path> files) throws Exception {
        var copies = new java.util.ArrayList<Path>();
        for (Path f : files) {
            Path c = Files.createTempFile(dir, "u", f.getFileName().toString());
            Files.copy(f, c, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            copies.add(c);
        }
        long id = batches.create(athlete, kind, "t", null, coach.id());
        runner.run(id, athlete, kind, copies, null);
        assertThat(batches.find(id).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    private long athlete(Integer hrMax) {
        return jdbc.sql("INSERT INTO athlete (name, sex, hr_max, resting_hr) VALUES ('M', 'M', ?, 48) RETURNING id")
                .param(hrMax).query(Long.class).single();
    }

    @Test
    void recomputeBuildsLoadsAndDayFeatures() throws Exception {
        long a = athlete(190);
        importAll(a);

        mvc.perform(post("/api/athletes/" + a + "/features/recompute").with(user(coach)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.load.fromStream").value(DAYS))
                .andExpect(jsonPath("$.features.paired").value(DAYS));

        // Every Garmin run got a stream-based load; the FIT run is 120 s at HR 130-159.
        assertThat(count("SELECT count(*) FROM activity_load WHERE athlete_id = ? AND method = 'stream' AND trimp_banister > 0", a)).isEqualTo(DAYS);
        var load = jdbc.sql("SELECT trimp_banister, hr_coverage, hr_max, hr_rest FROM activity_load WHERE athlete_id = ? LIMIT 1")
                .param(a).query().singleRow();
        assertThat(((Number) load.get("hr_max")).intValue()).isEqualTo(190);
        assertThat(((Number) load.get("hr_rest")).intValue()).isEqualTo(48);
        assertThat(((Number) load.get("trimp_banister")).doubleValue()).isBetween(1.0, 5.0);

        // Whoop targets joined by cycle date; baselines appear after 14 prior days.
        var first = jdbc.sql("SELECT * FROM day_features WHERE athlete_id = ? AND cycle_date = ?").params(a, FIRST).query().singleRow();
        assertThat(first.get("whoop_recovery")).isEqualTo(40);
        assertThat(first.get("has_garmin_core")).isEqualTo(true);
        assertThat(first.get("ln_rmssd_z")).isNull();
        assertThat(((Number) first.get("rhr")).intValue()).isEqualTo(47);
        assertThat(((Number) first.get("sleep_minutes")).intValue()).isEqualTo(420); // deep+light+rem: 6000+12000+7200 s
        assertThat(count("SELECT count(*) FROM day_features WHERE athlete_id = ? AND ln_rmssd_z IS NOT NULL", a)).isEqualTo(DAYS - 14);
        assertThat(count("SELECT count(*) FROM day_features WHERE athlete_id = ? AND baseline_days >= 14 AND rhr_dev IS NOT NULL", a)).isEqualTo(DAYS - 14);

        // Idempotent.
        mvc.perform(post("/api/athletes/" + a + "/features/recompute").with(user(coach)).with(csrf())).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM day_features WHERE athlete_id = ?", a)).isEqualTo(DAYS);
        assertThat(count("SELECT count(*) FROM activity_load WHERE athlete_id = ?", a)).isEqualTo(DAYS);

        mvc.perform(get("/api/athletes/" + a + "/features/summary").with(user(coach)))
                .andExpect(jsonPath("$.paired").value(DAYS))
                .andExpect(jsonPath("$.activityLoadMethods.stream").value(DAYS));
    }

    @Test
    void withoutHrMaxLoadIsUnknownNotZero() throws Exception {
        long a = athlete(null);
        importAll(a);
        mvc.perform(post("/api/athletes/" + a + "/features/recompute").with(user(coach)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.load.withoutLoad").value(DAYS));
        assertThat(count("SELECT count(*) FROM day_features WHERE athlete_id = ? AND day_load_banister IS NULL AND load_missing_activities = 1", a)).isEqualTo(DAYS);
    }

    @Test
    void athletesCannotRecomputeAndReportsAreScriptFree() throws Exception {
        long a = athlete(190);
        long uid = users.insert("ath-m" + System.nanoTime(), "x", Role.ATHLETE, a);
        var athleteUser = users.findById(uid).orElseThrow();
        mvc.perform(post("/api/athletes/" + a + "/features/recompute").with(user(athleteUser)).with(csrf()))
                .andExpect(status().isForbidden());

        long model = jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, 'm-test', 'f1', '{}', '{"recovery":{"band_agreement":0.75}}', true, '<html><body>ok</body></html>') RETURNING id""")
                .param(a).query(Long.class).single();
        mvc.perform(get("/api/athletes/" + a + "/models").with(user(athleteUser)))
                .andExpect(jsonPath("$[0].version").value("m-test"))
                .andExpect(jsonPath("$[0].passed").value(true));
        mvc.perform(get("/api/models/" + model + "/report").with(user(athleteUser)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")));
        long other = athlete(190);
        long otherUser = users.insert("ath-o" + System.nanoTime(), "x", Role.ATHLETE, other);
        mvc.perform(get("/api/models/" + model + "/report").with(user(users.findById(otherUser).orElseThrow())))
                .andExpect(status().isForbidden());
    }
}
