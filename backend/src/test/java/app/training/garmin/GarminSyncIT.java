package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.common.ApiException;
import app.training.garmin.GarminSyncRunRepository.Status;
import app.training.garmin.GarminSyncRunRepository.Trigger;
import app.training.ingestion.Fixtures;
import app.training.ingestion.ImportBatchRepository;
import app.training.ingestion.ImportKind;
import app.training.ingestion.ImportRunner;
import app.training.metrics.DailyScorerParityTest;
import app.training.metrics.FeatureRecomputeService;
import app.training.metrics.holdout.HoldoutRepository;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Garmin daily sync end to end, with a mocked Garmin (the fake sidecar) and a real database
 * (stage 1b-4 "done when"): today's scores without a manual import, a second sync of the same
 * morning changes nothing, a later sync updates inputs and score while keeping the earlier
 * inputs, synced days land exactly like exported ones, and failures are reported. Synthetic data.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GarminSyncIT extends GarminTestBase {
    static final LocalDate FIRST = LocalDate.of(2026, 2, 1);
    static final int EXPORTED = 40;          // days 0..39 come from an export
    static final LocalDate TODAY = FIRST.plusDays(EXPORTED + 2); // the sync fetches days 40, 41 and 42
    static final String TOKENS = "{\"di_token\":\"fake-access\",\"di_refresh_token\":\"fake-refresh\"}";

    @Autowired MockMvc mvc;
    @Autowired ImportRunner runner;
    @Autowired ImportBatchRepository batches;
    @Autowired UserRepository users;
    @Autowired GarminSyncService sync;
    @Autowired GarminSyncRunRepository runs;
    @Autowired GarminCredentialRepository credentials;
    @Autowired GarminSetup setup;
    @Autowired FeatureRecomputeService recompute;
    @Autowired HoldoutRepository holdout;
    @TempDir Path dir;

    CurrentUser coach;

    @BeforeEach
    void setUp() {
        SIDECAR.reset();
        long id = users.insert("coach-sync-" + System.nanoTime(), "x", Role.COACH, null);
        coach = users.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private long athlete() {
        return jdbc.sql("INSERT INTO athlete (name, sex, hr_max, resting_hr) VALUES ('Sync', 'M', 190, 48) RETURNING id")
                .query(Long.class).single();
    }

    private void importExport(long athlete, int days) throws Exception {
        Path sub = Files.createTempDirectory(dir, "export");
        Path zip = Fixtures.garminExport(sub, FIRST, days);
        long batch = batches.create(athlete, ImportKind.GARMIN_EXPORT, "export", null, coach.id());
        runner.run(batch, athlete, ImportKind.GARMIN_EXPORT, List.of(zip), null);
        assertThat(batches.find(batch).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    private void activeModel(long athlete) throws Exception {
        var coefficients = DailyScorerParityTest.asContract(new ObjectMapper()
                .readTree(DailyScorerParityTest.resource("adr0008", "expected.json")).get("coefficients"));
        jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, 'm-sync', 'ACTIVE', 'f3', ?::jsonb, '{}', true, '')""")
                .params(athlete, coefficients.toString()).update();
    }

    private void connect(long athlete) {
        var cipher = setup.cipher().orElseThrow();
        credentials.saveConnected(athlete, cipher.encrypt(athlete, TOKENS.getBytes(StandardCharsets.UTF_8)), cipher.keyId(), Instant.now());
    }

    private void garminReturns(String payload) {
        SIDECAR.reply("/sync/days", 200, payload);
    }

    /** An athlete with 40 exported days, scored by an ACTIVE model, connected to Garmin. */
    private long exportedAndScored() throws Exception {
        long a = athlete();
        importExport(a, EXPORTED);
        activeModel(a);
        recompute.recompute(a, coach.id(), FeatureRecomputeService.SOURCE_MANUAL);
        connect(a);
        return a;
    }

    private Map<LocalDate, String> scores(long athlete) {
        var out = new java.util.TreeMap<LocalDate, String>();
        jdbc.sql("""
                SELECT cycle_date, recovery_pct, recovery_band, strain, sleep_performance_pct FROM derived_day_metrics
                WHERE athlete_id = ? AND model_version = 'm-sync'""").param(athlete)
                .query(rs -> {
                    out.put(rs.getObject(1, LocalDate.class), rs.getString(2) + "/" + rs.getString(3) + "/" + rs.getString(4) + "/" + rs.getString(5));
                });
        return out;
    }

    private Integer recovery(long athlete, LocalDate day) {
        return jdbc.sql("SELECT recovery_pct FROM derived_day_metrics WHERE athlete_id = ? AND model_version = 'm-sync' AND cycle_date = ?")
                .params(athlete, day).query(Integer.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ done-when tests

    @Test
    void morningSyncScoresTodayAndASecondSyncChangesNothing(CapturedOutput output) throws Exception {
        long a = exportedAndScored();
        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 3, TOKENS).toString());

        var run = sync.runNow(a, Trigger.MORNING, TODAY);
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(run.firstDay()).isEqualTo(TODAY.minusDays(2));
        assertThat(run.rowsInserted()).isPositive();
        assertThat(run.recomputed()).isTrue();
        // The sidecar was asked for today and the 2 days before, with the stored token in the body.
        var req = SIDECAR.received.getLast();
        assertThat(req.secret()).isEqualTo("test-sync-secret");
        assertThat(req.body()).contains("fake-refresh").contains(TODAY.toString()).contains(TODAY.minusDays(2).toString());
        assertThat(req.path()).isEqualTo("/sync/days");

        // Today's scores exist without any manual import, recovery included.
        assertThat(recovery(a, TODAY)).isNotNull();
        assertThat(scores(a)).containsKeys(TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
        assertThat(count("SELECT count(*) FROM import_batch WHERE athlete_id = ? AND kind = 'GARMIN_SYNC' AND status = 'SUCCEEDED'", a))
                .isEqualTo(1);
        // The recompute is logged as a sync run, and the Holdout markers show only the manual one (ADR 0015 D8).
        assertThat(count("SELECT count(*) FROM feature_recompute_log WHERE athlete_id = ? AND source = 'garmin-sync'", a)).isEqualTo(1);
        assertThat(holdout.recomputeRuns(a, null, null)).hasSize(1);

        var before = scores(a);
        long rawBefore = count("SELECT (SELECT count(*) FROM daily_wellness WHERE athlete_id = ?) + (SELECT count(*) FROM sleep_session"
                + " WHERE athlete_id = ?) + (SELECT count(*) FROM monitoring_hr WHERE athlete_id = ?) + (SELECT count(*) FROM activity"
                + " WHERE athlete_id = ?)", a, a, a, a);

        var second = sync.runNow(a, Trigger.MANUAL, TODAY);
        assertThat(second.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(second.rowsInserted()).isZero();
        assertThat(second.recomputed()).isFalse();
        assertThat(scores(a)).isEqualTo(before);
        assertThat(count("SELECT (SELECT count(*) FROM daily_wellness WHERE athlete_id = ?) + (SELECT count(*) FROM sleep_session"
                + " WHERE athlete_id = ?) + (SELECT count(*) FROM monitoring_hr WHERE athlete_id = ?) + (SELECT count(*) FROM activity"
                + " WHERE athlete_id = ?)", a, a, a, a)).isEqualTo(rawBefore);
        assertThat(count("SELECT count(*) FROM feature_recompute_log WHERE athlete_id = ? AND source = 'garmin-sync'", a)).isEqualTo(1);

        assertThat(output.getAll()).doesNotContain("fake-access").doesNotContain("fake-refresh").doesNotContain("test-sync-secret");
    }

    @Test
    void aLaterSyncOfTheSameDayUpdatesInputsAndScoreAndKeepsTheEarlierInputs() throws Exception {
        long a = exportedAndScored();
        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 3, TOKENS).toString());
        sync.runNow(a, Trigger.MORNING, TODAY);
        Integer morning = recovery(a, TODAY);
        var earlierDays = scores(a);
        earlierDays.remove(TODAY);

        // Later that day Garmin has re-processed the night: a different resting HR and HRV for today.
        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 3, TOKENS, day -> {
            if (day.get("date").asText().equals(TODAY.toString())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) day.get("summary")).put("restingHeartRate", 58);
                ((com.fasterxml.jackson.databind.node.ObjectNode) day.get("hrv")).put("lastNightAvg", 35.0);
            }
        }).toString());
        var later = sync.runNow(a, Trigger.MANUAL, TODAY);
        assertThat(later.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(later.rowsInserted()).isPositive();
        assertThat(later.recomputed()).isTrue();

        // Both versions are in the raw table; the newest one counts (ADR 0010).
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ? AND calendar_date = ? AND source_id = 'uds'", a, TODAY))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ? AND calendar_date = ? AND source_id LIKE 'uds@%'", a, TODAY))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ? AND calendar_date = ? AND resting_hr = 58", a, TODAY))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM daily_wellness_merged WHERE athlete_id = ? AND calendar_date = ? AND resting_hr = 58", a, TODAY))
                .isEqualTo(1);
        assertThat(recovery(a, TODAY)).isNotNull().isNotEqualTo(morning);
        var after = scores(a);
        after.remove(TODAY);
        assertThat(after).isEqualTo(earlierDays);
    }

    @Test
    void syncedDaysLandExactlyLikeExportedDays() throws Exception {
        long exported = athlete();
        importExport(exported, 3);
        long synced = athlete();
        connect(synced);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS).toString());
        assertThat(sync.runNow(synced, Trigger.MANUAL, FIRST.plusDays(2)).status()).isEqualTo(Status.SUCCEEDED);

        // Every column the day features read, plus the rest of each row (hrv_status aside, ADR 0015 D5).
        for (String sql : List.of(
                "SELECT calendar_date, resting_hr, hrv_rmssd_ms, body_battery_high, body_battery_low, respiration_rate, spo2_pct, stress_avg"
                        + " FROM daily_wellness_merged WHERE athlete_id = ? ORDER BY 1",
                "SELECT start_at, end_at, cycle_date, deep_s, light_s, rem_s, awake_s, sleep_score, avg_respiration, avg_sleep_stress,"
                        + " avg_spo2_pct, lowest_spo2_pct FROM sleep_session_current WHERE athlete_id = ? ORDER BY 1",
                "SELECT source_id, sport, source_sport, start_at, calendar_date, duration_s, distance_m, elevation_gain_m, avg_hr, max_hr,"
                        + " calories, training_load, aerobic_te, anaerobic_te FROM activity WHERE athlete_id = ? ORDER BY start_at",
                "SELECT count(*), sum(s.hr), min(s.ts), max(s.ts) FROM activity_stream s JOIN activity x ON x.id = s.activity_id"
                        + " WHERE x.athlete_id = ?",
                "SELECT count(*), sum(hr), min(ts), max(ts) FROM monitoring_hr WHERE athlete_id = ?")) {
            var fromExport = jdbc.sql(sql).param(exported).query().listOfRows();
            var fromSync = jdbc.sql(sql).param(synced).query().listOfRows();
            assertThat(fromExport).as(sql).isNotEmpty();
            assertThat(fromSync).as(sql).isEqualTo(fromExport);
        }
        assertThat(count("SELECT count(*) FROM daily_wellness_merged WHERE athlete_id = ?", synced)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ failures

    @Test
    void garminRejectingTheTokenNeedsAReconnectAndIsNotRetried() throws Exception {
        long a = athlete();
        connect(a);
        SIDECAR.reply("/sync/days", 401, "{\"error\":\"AUTH_REJECTED\"}");
        var run = sync.runNow(a, Trigger.MORNING, TODAY);
        assertThat(run.status()).isEqualTo(Status.FAILED);
        assertThat(run.errorCode()).isEqualTo("AUTH_REJECTED");
        assertThat(run.nextRetryAt()).isNull();
        assertThat(credentials.find(a).orElseThrow().status()).isEqualTo("RECONNECT_NEEDED");
        assertThatThrownBy(() -> sync.runNow(a, Trigger.MANUAL, TODAY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("RECONNECT_NEEDED"));
        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(coach))).andExpect(jsonPath("$.state").value("RECONNECT_NEEDED"));
    }

    @Test
    void garminUnavailableIsRetriedThenSucceeds() throws Exception {
        long a = athlete();
        connect(a);
        SIDECAR.reply("/sync/days", 502, "{\"error\":\"GARMIN_UNAVAILABLE\"}");
        var run = sync.runNow(a, Trigger.MORNING, TODAY);
        assertThat(run.status()).isEqualTo(Status.RETRY_WAIT);
        assertThat(run.errorCode()).isEqualTo("GARMIN_UNAVAILABLE");
        assertThat(run.nextRetryAt()).isBetween(Instant.now().plusSeconds(9 * 60), Instant.now().plusSeconds(11 * 60));

        sync.retryDueNow(); // not due yet
        assertThat(runs.find(run.id()).orElseThrow().status()).isEqualTo(Status.RETRY_WAIT);

        jdbc.sql("UPDATE garmin_sync_run SET next_retry_at = now() - interval '1 minute' WHERE id = ?").param(run.id()).update();
        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 3, TOKENS).toString());
        sync.retryDueNow();
        var done = runs.find(run.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(done.attempt()).isEqualTo(2);
        assertThat(done.errorCode()).isNull();
    }

    @Test
    void afterTheLastRetryTheRunFails() throws Exception {
        long a = athlete();
        connect(a);
        SIDECAR.reply("/sync/days", 429, "{\"error\":\"RATE_LIMITED\"}");
        var run = sync.runNow(a, Trigger.MORNING, TODAY);
        for (int i = 0; i < 2; i++) {
            jdbc.sql("UPDATE garmin_sync_run SET next_retry_at = now() - interval '1 minute' WHERE id = ?").param(run.id()).update();
            sync.retryDueNow();
        }
        var last = runs.find(run.id()).orElseThrow();
        assertThat(last.attempt()).isEqualTo(3);
        assertThat(last.status()).isEqualTo(Status.FAILED);
        assertThat(last.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(credentials.find(a).orElseThrow().status()).isEqualTo("CONNECTED");
    }

    @Test
    void aRefreshedTokenIsStoredEncrypted() throws Exception {
        long a = athlete();
        connect(a);
        String renewed = "{\"di_token\":\"fake-renewed\",\"di_refresh_token\":\"fake-refresh\"}";
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 1, renewed).toString());
        sync.runNow(a, Trigger.MANUAL, FIRST);
        var row = credentials.find(a).orElseThrow();
        assertThat(row.tokenRefreshedAt()).isNotNull();
        assertThat(new String(setup.cipher().orElseThrow().decrypt(a, row.tokenCiphertext()), StandardCharsets.UTF_8)).isEqualTo(renewed);
    }

    @Test
    void syncNeedsAConnectionAndOnlyOneRunAtATime() throws Exception {
        long a = athlete();
        assertThatThrownBy(() -> sync.runNow(a, Trigger.MANUAL, TODAY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_CONNECTED"));
        connect(a);
        jdbc.sql("INSERT INTO garmin_sync_run (athlete_id, trigger, status, first_day, last_day) VALUES (?, 'MORNING', 'RUNNING', ?, ?)")
                .params(a, TODAY, TODAY).update();
        mvc.perform(post("/api/athletes/" + a + "/garmin/sync").with(user(coach)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SYNC_RUNNING"));
    }

    @Test
    void syncNowOverHttpAnswersAtOnceAndTheRunListShowsTheResult() throws Exception {
        long a = athlete();
        connect(a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS).toString());
        mvc.perform(post("/api/athletes/" + a + "/garmin/sync").with(user(coach)).with(csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RUNNING"));
        for (int i = 0; i < 100 && runs.active(a).isPresent(); i++) Thread.sleep(100);
        mvc.perform(get("/api/athletes/" + a + "/garmin/sync/runs").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"));
    }

    // ------------------------------------------------------------------ check against export

    @Test
    void checkAgainstExportKeepsNothingAndReportsOnlyHarmlessDifferences() throws Exception {
        long a = athlete();
        importExport(a, 5);
        connect(a);
        long rawBefore = count("SELECT (SELECT count(*) FROM daily_wellness WHERE athlete_id = ?) + (SELECT count(*) FROM monitoring_hr"
                + " WHERE athlete_id = ?) + (SELECT count(*) FROM import_batch WHERE athlete_id = ?)", a, a, a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 5, TOKENS).toString());

        mvc.perform(post("/api/athletes/" + a + "/garmin/check").with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"" + FIRST + "\",\"to\":\"" + FIRST.plusDays(4) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differencesUsedByScores").value(0))
                .andExpect(jsonPath("$.tables.sleep_session.inserted").value(0))
                .andExpect(jsonPath("$.tables.activity.inserted").value(0))
                .andExpect(jsonPath("$.tables.monitoring_hr.inserted").value(0))
                .andExpect(jsonPath("$.tables.activity_stream.inserted").value(0))
                // The live HRV status is left empty (another vocabulary); nothing reads it.
                .andExpect(jsonPath("$.differences[0].column").value("hrv_status"))
                .andExpect(jsonPath("$.differences[0].usedByScores").value(false));

        assertThat(count("SELECT (SELECT count(*) FROM daily_wellness WHERE athlete_id = ?) + (SELECT count(*) FROM monitoring_hr"
                + " WHERE athlete_id = ?) + (SELECT count(*) FROM import_batch WHERE athlete_id = ?)", a, a, a)).isEqualTo(rawBefore);
    }

    @Test
    void checkAgainstExportNamesAColumnTheScoresUse() throws Exception {
        long a = athlete();
        importExport(a, 3);
        connect(a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS,
                day -> ((com.fasterxml.jackson.databind.node.ObjectNode) day.get("summary")).put("restingHeartRate", 70)).toString());
        mvc.perform(post("/api/athletes/" + a + "/garmin/check").with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"" + FIRST + "\",\"to\":\"" + FIRST.plusDays(2) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differencesUsedByScores").value(3))
                .andExpect(jsonPath("$.differences[?(@.column == 'resting_hr')].outcome").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("DIFFERENT"))));
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ? AND resting_hr = 70", a)).isZero();
    }

    @Test
    void checkRefusesLongOrFutureRanges() throws Exception {
        long a = athlete();
        connect(a);
        LocalDate today = sync.today(a);
        for (String body : List.of(
                "{\"from\":\"" + today.minusDays(20) + "\",\"to\":\"" + today + "\"}",
                "{\"from\":\"" + today + "\",\"to\":\"" + today.plusDays(1) + "\"}",
                "{\"from\":\"" + today + "\",\"to\":\"" + today.minusDays(1) + "\"}")) {
            mvc.perform(post("/api/athletes/" + a + "/garmin/check").with(user(coach)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(SIDECAR.received).isEmpty();
    }
}
