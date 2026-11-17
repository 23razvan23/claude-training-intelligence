package app.training.garmin;

import app.training.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Connect, MFA, status and Disconnect (ADR 0011). The password and the MFA code pass straight
 * through to the sidecar and are never stored or logged; only the encrypted token is kept.
 */
@Service
public class GarminConnectionService {
    private static final Logger log = LoggerFactory.getLogger(GarminConnectionService.class);

    public enum State { NOT_CONFIGURED, NOT_CONNECTED, CONNECTED, RECONNECT_NEEDED }

    /** Fixed reason stored when the row was locked with a different GARMIN_TOKEN_KEY (ADR 0011 D7). */
    public static final String KEY_CHANGED = "KEY_CHANGED";

    /**
     * What the UI shows. Never the token or anything from Garmin (ADR 0011 D3).
     *
     * @param stored a credential row exists (so Disconnect is offered even while sync is not configured)
     */
    public record Status(State state, boolean stored, Instant connectedAt, Instant tokenRefreshedAt, String lastError) {}

    /** CONNECTED, or MFA_REQUIRED with the one-time id to send back with the code. */
    public record ConnectResult(String status, String mfaId) {}

    private final GarminSetup setup;
    private final GarminCredentialRepository credentials;
    private final SidecarClient sidecar;
    private final Clock clock;

    public GarminConnectionService(GarminSetup setup, GarminCredentialRepository credentials, SidecarClient sidecar, Clock clock) {
        this.setup = setup;
        this.credentials = credentials;
        this.sidecar = sidecar;
        this.clock = clock;
    }

    public Status status(long athleteId) {
        var row = credentials.find(athleteId);
        if (!setup.enabled()) {
            return new Status(State.NOT_CONFIGURED, row.isPresent(), row.map(r -> r.connectedAt()).orElse(null), null, null);
        }
        if (row.isEmpty()) return new Status(State.NOT_CONNECTED, false, null, null, null);
        var r = row.get();
        String currentKey = setup.cipher().orElseThrow().keyId();
        if (!currentKey.equals(r.keyId())) {
            return new Status(State.RECONNECT_NEEDED, true, r.connectedAt(), r.tokenRefreshedAt(), KEY_CHANGED);
        }
        return new Status(State.valueOf(r.status()), true, r.connectedAt(), r.tokenRefreshedAt(), r.lastError());
    }

    public ConnectResult connect(long athleteId, String username, String password) {
        requireConfigured();
        try {
            return finish(athleteId, sidecar.login(username, password));
        } catch (SidecarClient.SidecarException e) {
            throw failure(athleteId, "connect", e.code());
        }
    }

    public ConnectResult mfa(long athleteId, String mfaId, String code) {
        requireConfigured();
        try {
            return finish(athleteId, sidecar.mfa(mfaId, code));
        } catch (SidecarClient.SidecarException e) {
            throw failure(athleteId, "MFA", e.code());
        }
    }

    public void disconnect(long athleteId) {
        if (credentials.delete(athleteId)) log.info("Garmin disconnected for athlete {}", athleteId);
    }

    private ConnectResult finish(long athleteId, SidecarClient.LoginResult result) {
        if ("MFA_REQUIRED".equals(result.status())) return new ConnectResult("MFA_REQUIRED", result.mfaId());
        var cipher = setup.cipher().orElseThrow();
        byte[] sealed = cipher.encrypt(athleteId, result.tokens().getBytes(StandardCharsets.UTF_8));
        credentials.saveConnected(athleteId, sealed, cipher.keyId(), Instant.now(clock));
        log.info("Garmin connected for athlete {}", athleteId);
        return new ConnectResult("CONNECTED", null);
    }

    private void requireConfigured() {
        if (!setup.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Garmin sync is not configured", "NOT_CONFIGURED");
        }
    }

    /** One log line with the fixed code; a user-facing message for the same code (ADR 0011 D9). */
    private static ApiException failure(long athleteId, String step, String code) {
        log.warn("Garmin {} failed for athlete {}: {}", step, athleteId, code);
        return switch (code) {
            case "AUTH_FAILED" -> new ApiException(HttpStatus.BAD_REQUEST,
                    "Garmin did not accept that e-mail and password", code);
            case "MFA_INVALID" -> new ApiException(HttpStatus.BAD_REQUEST, "That code was not accepted", code);
            case "MFA_EXPIRED" -> new ApiException(HttpStatus.GONE, "The code request expired; connect again", code);
            case "RATE_LIMITED" -> new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Garmin is limiting sign-ins; wait a few minutes", code);
            case "GARMIN_UNAVAILABLE" -> new ApiException(HttpStatus.BAD_GATEWAY, "Garmin could not be reached", code);
            case "SIDECAR_UNREACHABLE" -> new ApiException(HttpStatus.BAD_GATEWAY,
                    "The garmin-sync service is not running", code);
            case "BAD_SECRET", "NOT_CONFIGURED" -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The app and the garmin-sync service do not share the same GARMIN_SYNC_SECRET", "SECRET_MISMATCH");
            default -> new ApiException(HttpStatus.BAD_GATEWAY, "Garmin sync failed", "SYNC_ERROR");
        };
    }
}
