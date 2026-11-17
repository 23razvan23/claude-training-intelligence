package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Connect, MFA, status and Disconnect against a real database and a fake sidecar (ADR 0011).
 * Fake credentials only; the logs are checked for every secret.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GarminConnectIT extends IntegrationTest {
    static final FakeSidecar SIDECAR = new FakeSidecar();
    static final String KEY;

    static {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        KEY = Base64.getEncoder().encodeToString(k);
    }

    static final String TOKENS = "{\\\"di_token\\\":\\\"fake-access\\\",\\\"di_refresh_token\\\":\\\"fake-refresh\\\"}";
    static final String CONNECT = "{\"username\":\"test-user@example.invalid\",\"password\":\"test-password\"}";

    @DynamicPropertySource
    static void garmin(DynamicPropertyRegistry r) {
        r.add("app.garmin.token-key", () -> KEY);
        r.add("app.garmin.sync-secret", () -> "test-sync-secret");
        r.add("app.garmin.sidecar-url", SIDECAR::url);
        r.add("app.garmin.sidecar-timeout-seconds", () -> 5);
    }

    @AfterAll
    static void stop() {
        SIDECAR.close();
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired GarminSetup setup;

    CurrentUser coach;

    @BeforeEach
    void setUp() {
        SIDECAR.reset();
        long id = users.insert("coach-garmin-" + System.nanoTime(), "x", Role.COACH, null);
        coach = users.findById(id).orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(long athlete, String path, String body) throws Exception {
        return mvc.perform(post("/api/athletes/" + athlete + "/garmin" + path).with(user(coach)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void connectStoresOnlyTheEncryptedTokenAndSendsTheSecretHeader(CapturedOutput output) throws Exception {
        long a = newAthlete("Garmin A");
        SIDECAR.reply("/auth/login", 200, "{\"status\":\"CONNECTED\",\"tokens\":\"" + TOKENS + "\"}");

        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(coach)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_CONNECTED"));
        postJson(a, "/connect", CONNECT).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONNECTED"));

        assertThat(SIDECAR.received).hasSize(1);
        var req = SIDECAR.received.get(0);
        assertThat(req.secret()).isEqualTo("test-sync-secret");
        assertThat(req.body()).contains("test-password"); // in the body, never a URL
        assertThat(req.path()).isEqualTo("/auth/login");

        byte[] stored = jdbc.sql("SELECT token_ciphertext FROM garmin_credential WHERE athlete_id = ?").param(a)
                .query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("fake-refresh").doesNotContain("test-password");
        String plain = new String(setup.cipher().orElseThrow().decrypt(a, stored), StandardCharsets.UTF_8);
        assertThat(plain).contains("fake-refresh");
        assertThat(count("SELECT count(*) FROM garmin_credential WHERE athlete_id = ? AND key_id = ? AND status = 'CONNECTED'",
                a, setup.cipher().orElseThrow().keyId())).isEqualTo(1);

        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(coach)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONNECTED"))
                .andExpect(jsonPath("$.connectedAt").exists())
                .andExpect(jsonPath("$.tokenCiphertext").doesNotExist());
        assertNoSecretsIn(output.getAll());
    }

    @Test
    void mfaInTheSameForm(CapturedOutput output) throws Exception {
        long a = newAthlete("Garmin B");
        SIDECAR.reply("/auth/login", 200, "{\"status\":\"MFA_REQUIRED\",\"mfaId\":\"fake-mfa-id\"}");
        postJson(a, "/connect", CONNECT).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_REQUIRED"))
                .andExpect(jsonPath("$.mfaId").value("fake-mfa-id"));
        assertThat(count("SELECT count(*) FROM garmin_credential WHERE athlete_id = ?", a)).isZero();

        SIDECAR.reply("/auth/mfa", 401, "{\"error\":\"MFA_INVALID\"}");
        postJson(a, "/mfa", "{\"mfaId\":\"fake-mfa-id\",\"code\":\"000000\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MFA_INVALID"));

        SIDECAR.reply("/auth/mfa", 200, "{\"status\":\"CONNECTED\",\"tokens\":\"" + TOKENS + "\"}");
        postJson(a, "/mfa", "{\"mfaId\":\"fake-mfa-id\",\"code\":\" 123456 \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONNECTED"));
        assertThat(SIDECAR.received.get(2).body()).contains("\"code\":\"123456\"").contains("fake-mfa-id");
        assertThat(count("SELECT count(*) FROM garmin_credential WHERE athlete_id = ?", a)).isEqualTo(1);
        assertNoSecretsIn(output.getAll());
    }

    @Test
    void garminErrorsBecomeFixedCodesAndStoreNothing(CapturedOutput output) throws Exception {
        long a = newAthlete("Garmin C");
        SIDECAR.reply("/auth/login", 401, "{\"error\":\"AUTH_FAILED\"}");
        postJson(a, "/connect", CONNECT).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH_FAILED"));
        SIDECAR.reply("/auth/login", 429, "{\"error\":\"RATE_LIMITED\"}");
        postJson(a, "/connect", CONNECT).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        SIDECAR.reply("/auth/login", 401, "{\"error\":\"BAD_SECRET\"}");
        postJson(a, "/connect", CONNECT).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECRET_MISMATCH"));
        SIDECAR.reply("/auth/login", 500, "<html>Garmin said: test-password</html>");
        postJson(a, "/connect", CONNECT).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("SYNC_ERROR"));
        SIDECAR.reply("/auth/mfa", 410, "{\"error\":\"MFA_EXPIRED\"}");
        postJson(a, "/mfa", "{\"mfaId\":\"x\",\"code\":\"1\"}").andExpect(status().isGone());
        assertThat(count("SELECT count(*) FROM garmin_credential WHERE athlete_id = ?", a)).isZero();
        assertNoSecretsIn(output.getAll());
    }

    @Test
    void invalidOrIncompleteBodiesAreRefusedWithoutEchoingThem(CapturedOutput output) throws Exception {
        long a = newAthlete("Garmin D");
        postJson(a, "/connect", "{\"username\":\"test-user\",\"password\":\"test-password\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid request body"));
        postJson(a, "/connect", "{\"username\":\"test-user\"}").andExpect(status().isBadRequest());
        assertThat(SIDECAR.received).isEmpty();
        assertNoSecretsIn(output.getAll());
    }

    @Test
    void aRowLockedWithAnotherKeyNeedsReconnectAndIsReplacedOnConnect() throws Exception {
        long a = newAthlete("Garmin E");
        jdbc.sql("""
                INSERT INTO garmin_credential (athlete_id, token_ciphertext, key_id, status, connected_at)
                VALUES (?, '\\x00'::bytea, 'deadbeef', 'CONNECTED', now())""").param(a).update();
        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(coach)))
                .andExpect(jsonPath("$.state").value("RECONNECT_NEEDED"))
                .andExpect(jsonPath("$.lastError").value("KEY_CHANGED"));
        SIDECAR.reply("/auth/login", 200, "{\"status\":\"CONNECTED\",\"tokens\":\"" + TOKENS + "\"}");
        postJson(a, "/connect", CONNECT).andExpect(status().isOk());
        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(coach))).andExpect(jsonPath("$.state").value("CONNECTED"));
    }

    @Test
    void disconnectDeletesTheRow() throws Exception {
        long a = newAthlete("Garmin F");
        SIDECAR.reply("/auth/login", 200, "{\"status\":\"CONNECTED\",\"tokens\":\"" + TOKENS + "\"}");
        postJson(a, "/connect", CONNECT).andExpect(status().isOk());
        mvc.perform(delete("/api/athletes/" + a + "/garmin").with(user(coach)).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM garmin_credential WHERE athlete_id = ?", a)).isZero();
        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(coach))).andExpect(jsonPath("$.state").value("NOT_CONNECTED"));
    }

    @Test
    void reconnectNeededIsReported() throws Exception {
        long a = newAthlete("Garmin G");
        SIDECAR.reply("/auth/login", 200, "{\"status\":\"CONNECTED\",\"tokens\":\"" + TOKENS + "\"}");
        postJson(a, "/connect", CONNECT).andExpect(status().isOk());
        new GarminCredentialRepository(jdbc).markReconnectNeeded(a, "AUTH_REJECTED", Instant.now());
        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(coach)))
                .andExpect(jsonPath("$.state").value("RECONNECT_NEEDED"))
                .andExpect(jsonPath("$.lastError").value("AUTH_REJECTED"));
    }

    @Test
    void athletesCannotUseTheseEndpoints() throws Exception {
        long a = newAthlete("Garmin H");
        long id = users.insert("athlete-garmin-" + System.nanoTime(), "x", Role.ATHLETE, a);
        var athlete = users.findById(id).orElseThrow();
        mvc.perform(get("/api/athletes/" + a + "/garmin").with(user(athlete))).andExpect(status().isForbidden());
        mvc.perform(post("/api/athletes/" + a + "/garmin/connect").with(user(athlete)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CONNECT)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/athletes/" + a + "/garmin").with(user(athlete)).with(csrf())).andExpect(status().isForbidden());
        assertThat(SIDECAR.received).isEmpty();
    }

    @Test
    void sidecarDownIsReportedPlainly() throws Exception {
        long a = newAthlete("Garmin I");
        var props = new GarminProperties(KEY, "test-sync-secret", "http://127.0.0.1:1", 2);
        var client = new SidecarClient(props, new GarminSetup(props), new com.fasterxml.jackson.databind.ObjectMapper());
        var service = new GarminConnectionService(new GarminSetup(props), new GarminCredentialRepository(jdbc), client,
                java.time.Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.connect(a, "test-user", "test-password"))
                .isInstanceOfSatisfying(app.training.common.ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("SIDECAR_UNREACHABLE"));
    }

    static void assertNoSecretsIn(String log) {
        for (String s : new String[] {"test-password", "test-user@example.invalid", "fake-access", "fake-refresh",
                "test-sync-secret", "123456", "fake-mfa-id", KEY}) {
            assertThat(log).as("log must not contain " + s).doesNotContain(s);
        }
    }
}
