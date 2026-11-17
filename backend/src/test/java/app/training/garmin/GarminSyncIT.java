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
import java.math.BigDecimal;
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

        // Every column the day features read, plus the rest of each row (hrv_status and the unused
        // sleep stress aside: Garmin's live answer may not carry them in the export's form, ADR 0015 D5, D11).
        for (String sql : List.of(
                "SELECT calendar_date, resting_hr, hrv_rmssd_ms, body_battery_high, body_battery_low, respiration_rate, spo2_pct, stress_avg"
                        + " FROM daily_wellness_merged WHERE athlete_id = ? ORDER BY 1",
                "SELECT start_at, end_at, cycle_date, deep_s, light_s, rem_s, awake_s, sleep_score, avg_respiration,"
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
                // Exported nights win (ADR 0015 D12): a synced night is not even written, so no sleep difference.
                .andExpect(jsonPath("$.differences[?(@.table == 'sleep_session')]").isEmpty())
                .andExpect(jsonPath("$.tables.sleep_session.inserted").value(0))
                .andExpect(jsonPath("$.differences[?(@.usedByScores == true)]").isEmpty())
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
    void checkAgainstExportFindsWhereThePreciseSleepValuesAre() throws Exception {
        long a = athlete();
        importExport(a, 3);
        connect(a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS).toString());
        mvc.perform(post("/api/athletes/" + a + "/garmin/check").with(user(coach)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"" + FIRST + "\",\"to\":\"" + FIRST.plusDays(2) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sleepFieldSearch[?(@.column == 'avg_respiration')].nights").value(3))
                .andExpect(jsonPath("$.sleepFieldSearch[?(@.column == 'avg_respiration')].candidates[0].path")
                        .value("dailySleepDTO.averageRespirationValue"))
                .andExpect(jsonPath("$.sleepFieldSearch[?(@.column == 'avg_respiration')].candidates[0].matches").value(3))
                .andExpect(jsonPath("$.sleepFieldSearch[?(@.column == 'avg_spo2_pct')].candidates[0].path")
                        .value("dailySleepDTO.averageSpO2Value"));
    }

    /** The coach's choice (ADR 0015 D12): without a precise field, Garmin's live sleep values are stored. */
    @Test
    void aSyncedNightStoresGarminsLiveSleepValues() throws Exception {
        long a = athlete();
        connect(a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 1, TOKENS, day -> {
            var dto = (com.fasterxml.jackson.databind.node.ObjectNode) day.get("sleep").get("dailySleepDTO");
            dto.put("averageRespirationValue", 13.0).put("averageSpO2Value", 96.0);
            ((com.fasterxml.jackson.databind.node.ObjectNode) day.get("sleep")).remove("wellnessSpO2SleepSummaryDTO");
        }).toString());
        assertThat(sync.runNow(a, Trigger.MANUAL, FIRST).status()).isEqualTo(Status.SUCCEEDED);
        var row = jdbc.sql("SELECT deep_s, sleep_score, avg_respiration, avg_spo2_pct, lowest_spo2_pct FROM sleep_session WHERE athlete_id = ?")
                .param(a).query().singleRow();
        assertThat(row.get("deep_s")).isEqualTo(6000);
        assertThat(row.get("sleep_score")).isEqualTo(81);
        assertThat((BigDecimal) row.get("avg_respiration")).isEqualByComparingTo("13.0");
        assertThat((BigDecimal) row.get("avg_spo2_pct")).isEqualByComparingTo("96.0");
        assertThat((BigDecimal) row.get("lowest_spo2_pct")).isEqualByComparingTo("86.0");
    }

    /** ADR 0015 D12: a sync never replaces a night already imported from an export. */
    @Test
    void anExportedNightAlwaysWinsOverASyncedOne() throws Exception {
        long a = athlete();
        importExport(a, 3);
        connect(a);
        long nightsBefore = count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", a);
        // Garmin's live values differ from the export (as on the coach's data).
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS, day ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) day.get("sleep").get("dailySleepDTO")).put("averageRespirationValue", 12.0))
                .toString());
        var run = sync.runNow(a, Trigger.MANUAL, FIRST.plusDays(2));
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", a)).isEqualTo(nightsBefore);
        assertThat(count("SELECT count(*) FROM sleep_session_current WHERE athlete_id = ? AND avg_respiration = 12.5", a)).isEqualTo(3);
        var stats = jdbc.sql("SELECT stats::text FROM import_batch WHERE id = ?").param(run.importBatchId()).query(String.class).single();
        assertThat(stats).contains("\"sleep_session\"");

        // A night not in the export is synced as usual.
        garminReturns(GarminApiFixtures.payload(FIRST, 3, 1, TOKENS).toString());
        sync.runNow(a, Trigger.MANUAL, FIRST.plusDays(3));
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", a)).isEqualTo(nightsBefore + 1);
    }

    /** ADR 0015 D12: Today says when the shown day's breathing rate came from Garmin's live data. */
    @Test
    void todayMarksADayScoredWithLiveBreathing() throws Exception {
        long a = exportedAndScored();
        mvc.perform(get("/api/athletes/" + a + "/today").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastScored.liveBreathing").value(false));
        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 3, TOKENS).toString());
        sync.runNow(a, Trigger.MORNING, TODAY);
        mvc.perform(get("/api/athletes/" + a + "/today").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastScored.date").value(TODAY.toString()))
                .andExpect(jsonPath("$.lastScored.liveBreathing").value(true));
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
    // ------------------------------------------------------------------ the morning run (ADR 0015 D9), fake clock

    private static final java.time.ZoneId BUCHAREST = java.time.ZoneId.of("Europe/Bucharest");

    private static Instant local(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(BUCHAREST).toInstant();
    }

    private List<GarminSyncRunRepository.Run> morningRunsFor(long athlete, Instant now) {
        return sync.morningDueNow(now).stream().filter(r -> r.athleteId() == athlete).toList();
    }

    @Test
    void theMorningRunStartsAtSevenOnceAndCatchesUpAfterTheMacSlept() throws Exception {
        long a = exportedAndScored();
        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 3, TOKENS).toString());

        assertThat(morningRunsFor(a, local(TODAY, 6, 59))).isEmpty();
        var seven = morningRunsFor(a, local(TODAY, 7, 0));
        assertThat(seven).hasSize(1);
        var run = seven.getFirst();
        assertThat(run.trigger()).isEqualTo(Trigger.MORNING);
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(run.firstDay()).isEqualTo(TODAY.minusDays(2));
        assertThat(run.lastDay()).isEqualTo(TODAY);
        assertThat(run.startedAt()).isEqualTo(local(TODAY, 7, 0));
        assertThat(recovery(a, TODAY)).isNotNull();

        // No double run: later checks the same day start nothing.
        assertThat(morningRunsFor(a, local(TODAY, 7, 1))).isEmpty();
        assertThat(morningRunsFor(a, local(TODAY, 23, 59))).isEmpty();

        // The next day the Mac slept through 07:00 and wakes at 15:00: the run starts then, once.
        LocalDate tomorrow = TODAY.plusDays(1);
        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 4, TOKENS).toString());
        assertThat(morningRunsFor(a, local(tomorrow, 6, 0))).isEmpty();
        var late = morningRunsFor(a, local(tomorrow, 15, 0));
        assertThat(late).hasSize(1);
        assertThat(late.getFirst().lastDay()).isEqualTo(tomorrow);
        assertThat(morningRunsFor(a, local(tomorrow, 15, 1))).isEmpty();
        assertThat(count("SELECT count(*) FROM garmin_sync_run WHERE athlete_id = ? AND trigger = 'MORNING'", a)).isEqualTo(2);
    }

    @Test
    void aMorningRunThatFailsIsRetriedButNotStartedAgain() throws Exception {
        long a = athlete();
        connect(a);
        SIDECAR.reply("/sync/days", 502, "{\"error\":\"GARMIN_UNAVAILABLE\"}");
        var first = morningRunsFor(a, local(TODAY, 7, 0));
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().status()).isEqualTo(Status.RETRY_WAIT);
        // While it waits to retry, and after it has failed for good, no second morning run starts.
        assertThat(morningRunsFor(a, local(TODAY, 7, 5))).isEmpty();
        jdbc.sql("UPDATE garmin_sync_run SET status = 'FAILED', next_retry_at = NULL WHERE id = ?").param(first.getFirst().id()).update();
        assertThat(morningRunsFor(a, local(TODAY, 9, 0))).isEmpty();
        assertThat(count("SELECT count(*) FROM garmin_sync_run WHERE athlete_id = ?", a)).isEqualTo(1);
    }

    @Test
    void aSuccessfulSyncNowAfterSevenCountsAsTheMorningRun() throws Exception {
        long a = athlete();
        connect(a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS).toString());
        // A Sync now at 06:30 doesn't count (Garmin may not have the night yet) ...
        long early = runs.start(a, Trigger.MANUAL, TODAY.minusDays(2), TODAY, local(TODAY, 6, 30)).orElseThrow();
        runs.succeed(early, null, 0, false, List.of(), local(TODAY, 6, 31));
        assertThat(morningRunsFor(a, local(TODAY, 7, 0))).hasSize(1);
        // ... one at 07:20 that succeeded does: the next day's 07:00 check after a 07:20 Sync now starts nothing.
        LocalDate tomorrow = TODAY.plusDays(1);
        long manual = runs.start(a, Trigger.MANUAL, tomorrow.minusDays(2), tomorrow, local(tomorrow, 7, 20)).orElseThrow();
        runs.succeed(manual, null, 0, false, List.of(), local(tomorrow, 7, 21));
        assertThat(morningRunsFor(a, local(tomorrow, 7, 30))).isEmpty();
    }

    @Test
    void noMorningRunWhileGarminNeedsAReconnectThenOneOnceReconnected() throws Exception {
        long a = athlete();
        connect(a);
        credentials.markReconnectNeeded(a, "AUTH_REJECTED", Instant.now());
        assertThat(morningRunsFor(a, local(TODAY, 7, 0))).isEmpty();
        assertThat(count("SELECT count(*) FROM garmin_sync_run WHERE athlete_id = ?", a)).isZero();
        // The coach connects again at 10:00 the same day: the morning run starts at the next check.
        connect(a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS).toString());
        assertThat(morningRunsFor(a, local(TODAY, 10, 1))).hasSize(1);
    }

    @Test
    void theSyncStatusSaysWhatHappenedAndAnAthleteCanReadOnlyTheirOwn() throws Exception {
        long a = athlete();
        mvc.perform(get("/api/athletes/" + a + "/garmin/sync/status").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.latestStatus").doesNotExist())
                .andExpect(jsonPath("$.morningTime").value("07:00:00"));
        connect(a);
        garminReturns(GarminApiFixtures.payload(FIRST, 0, 3, TOKENS).toString());
        var ok = sync.runNow(a, Trigger.MANUAL, FIRST.plusDays(2));
        SIDECAR.reply("/sync/days", 502, "{\"error\":\"GARMIN_UNAVAILABLE\"}");
        sync.runNow(a, Trigger.MORNING, FIRST.plusDays(3));

        long athleteUser = users.insert("athlete-sync-" + System.nanoTime(), "x", Role.ATHLETE, a);
        var own = users.findById(athleteUser).orElseThrow();
        mvc.perform(get("/api/athletes/" + a + "/garmin/sync/status").with(user(own)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection").value("CONNECTED"))
                .andExpect(jsonPath("$.lastSuccessThrough").value(ok.lastDay().toString()))
                .andExpect(jsonPath("$.lastSuccessAt").exists())
                .andExpect(jsonPath("$.latestStatus").value("RETRY_WAIT"))
                .andExpect(jsonPath("$.latestErrorCode").value("GARMIN_UNAVAILABLE"))
                .andExpect(jsonPath("$.latestTrigger").value("MORNING"))
                .andExpect(jsonPath("$.nextRetryAt").exists());
        long other = athlete();
        mvc.perform(get("/api/athletes/" + other + "/garmin/sync/status").with(user(own))).andExpect(status().isForbidden());
        // Garmin rejecting the token: the status says a reconnect is needed.
        jdbc.sql("UPDATE garmin_sync_run SET status = 'FAILED' WHERE athlete_id = ? AND status = 'RETRY_WAIT'").param(a).update();
        SIDECAR.reply("/sync/days", 401, "{\"error\":\"AUTH_REJECTED\"}");
        sync.runNow(a, Trigger.MANUAL, FIRST.plusDays(3));
        mvc.perform(get("/api/athletes/" + a + "/garmin/sync/status").with(user(own)))
                .andExpect(jsonPath("$.connection").value("RECONNECT_NEEDED"))
                .andExpect(jsonPath("$.latestErrorCode").value("AUTH_REJECTED"));
    }

    // ------------------------------------------------------------------ holdout guard (ADR 0015 D13)

    @Autowired app.training.metrics.holdout.HoldoutService holdoutService;

    /**
     * Bug found while planning D13: an export whose night equals the synced version was a duplicate
     * (ADR 0010 D1), so the synced row stayed the newest and the day could never be seen as exported.
     */
    @Test
    void anExportedNightIdenticalToASyncedOneIsStoredAsTheExportsVersion() throws Exception {
        long a = athlete();
        importExport(a, 3);
        connect(a);
        // Garmin's live night equals what the export will hold, value for value (the synthetic export's
        // sleep stress is 17.25; on real data a whole-number breathing rate and SpO2 can match too).
        garminReturns(GarminApiFixtures.payload(FIRST, 3, 1, TOKENS, day ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) day.get("sleep").get("dailySleepDTO")).put("avgSleepStress", 17.25))
                .toString());
        sync.runNow(a, Trigger.MANUAL, FIRST.plusDays(3));
        LocalDate night = FIRST.plusDays(3);
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ? AND cycle_date = ? AND avg_sleep_stress = 17.25", a, night))
                .isEqualTo(1);
        String kindOfCurrent = "SELECT b.kind FROM sleep_session_current s JOIN import_batch b ON b.id = s.import_batch_id "
                + "WHERE s.athlete_id = ? AND s.cycle_date = ? AND NOT s.is_nap";
        assertThat(jdbc.sql(kindOfCurrent).params(a, night).query(String.class).single()).isEqualTo("GARMIN_SYNC");

        importExport(a, 4); // the same night, identical values, now from an export
        assertThat(jdbc.sql(kindOfCurrent).params(a, night).query(String.class).single()).isEqualTo("GARMIN_EXPORT");
        long nights = count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", a);
        importExport(a, 4); // the same export again adds nothing
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", a)).isEqualTo(nights);
        // Rows the features read are unchanged (identical values): only where the night came from is new.
        assertThat(count("SELECT count(*) FROM sleep_session_current WHERE athlete_id = ? AND cycle_date = ?", a, night)).isEqualTo(1);
    }

    @Test
    void aSyncedNightWaitsForAGarminExportInTheHoldoutThenCounts() throws Exception {
        long a = exportedAndScored();
        LocalDate from = FIRST.plusDays(EXPORTED - 3); // days 37..42: 3 exported, 3 synced
        jdbc.sql("""
                UPDATE model_version SET holdout_targets = ?::jsonb WHERE athlete_id = ? AND version = 'm-sync'""")
                .params("""
                        {"recovery_band_agreement_min": 0.7, "recovery_green_red_max": 0.03, "strain_mae_max": 2.0,
                         "strain_spearman_min": 0.8, "strain_min_monitored_minutes": 600, "min_recovery_days": 90,
                         "confidence": 0.95, "interval": "wilson", "window_from": "%s", "protocol": "ADR 0009", "source": "test"}"""
                        .formatted(from), a)
                .update();
        for (LocalDate d = from; !d.isAfter(TODAY); d = d.plusDays(1)) {
            var start = d.minusDays(1).atTime(23, 0).toInstant(java.time.ZoneOffset.UTC);
            jdbc.sql("""
                    INSERT INTO whoop_reference (athlete_id, cycle_start, cycle_end, cycle_date, recovery_pct, day_strain,
                        sleep_performance_pct, sleep_need_min) VALUES (?, ?, ?, ?, 60, 10.0, 80, 470)""")
                    .params(a, java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(86_400)), d).update();
        }
        var before = holdoutService.report(a, null);
        int comparedBefore = before.cumulative().recovery().n();
        assertThat(before.markers().waitingForExportDays()).isZero();

        garminReturns(GarminApiFixtures.payload(FIRST, EXPORTED, 3, TOKENS).toString());
        assertThat(sync.runNow(a, Trigger.MORNING, TODAY).recomputed()).isTrue();
        assertThat(recovery(a, TODAY)).isNotNull();

        var synced = holdoutService.report(a, null);
        assertThat(synced.markers().waitingForExportDays()).isEqualTo(3);
        assertThat(synced.cumulative().recovery().n()).isEqualTo(comparedBefore);
        assertThat(synced.cumulative().groups().get("recovery")).containsEntry(HoldoutEvaluatorAccess.WAITS, 3);
        assertThat(synced.cumulative().groups().get("strain").get(HoldoutEvaluatorAccess.WAITS)).isEqualTo(3);
        assertThat(synced.days().stream().filter(d -> d.date().equals(TODAY)).findFirst().orElseThrow().recoveryGroup())
                .isEqualTo(HoldoutEvaluatorAccess.WAITS);
        var html = holdoutService.decisionReport(a, null).html();
        assertThat(html).contains("3 compared days wait for a Garmin export");

        // The export arrives: until the days are rescored they still wait (their scores used the live night) ...
        importExport(a, EXPORTED + 3);
        assertThat(holdoutService.report(a, null).markers().waitingForExportDays()).isEqualTo(3);
        // ... and after Recompute features they count.
        recompute.recompute(a, coach.id(), FeatureRecomputeService.SOURCE_MANUAL);
        var exported = holdoutService.report(a, null);
        assertThat(exported.markers().waitingForExportDays()).isZero();
        assertThat(exported.cumulative().recovery().n()).isEqualTo(comparedBefore + 3);
        assertThat(exported.cumulative().groups().get("recovery")).doesNotContainKey(HoldoutEvaluatorAccess.WAITS);
    }

    /** The group name, kept in one place for these tests. */
    private static final class HoldoutEvaluatorAccess {
        static final String WAITS = app.training.metrics.holdout.HoldoutEvaluator.WAITS_FOR_EXPORT;
    }
}
