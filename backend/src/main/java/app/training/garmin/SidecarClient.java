package app.training.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Talks to the garmin-sync sidecar over the internal Compose network (ADR 0011 D4). Secrets go
 * only in the JSON body and the X-Sync-Secret header, never in a URL. Failures come back as a
 * {@link SidecarException} with a fixed code; no response body or exception text is passed on.
 */
@Component
public class SidecarClient {
    public static final String SECRET_HEADER = "X-Sync-Secret";

    /** The sidecar answered with an error code, or could not be reached ({@code SIDECAR_UNREACHABLE}). */
    public static final class SidecarException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public SidecarException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** CONNECTED with the token bundle, or MFA_REQUIRED with the one-time id. */
    public record LoginResult(String status, String tokens, String mfaId) {
        @Override
        public String toString() {
            return "LoginResult[status=" + status + "]";
        }
    }

    private final GarminProperties props;
    private final GarminSetup setup;
    private final ObjectMapper json;
    private final HttpClient http;

    public SidecarClient(GarminProperties props, GarminSetup setup, ObjectMapper json) {
        this.props = props;
        this.setup = setup;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * The days' data and the token bundle to keep (ADR 0015 D2, D5). The tokens travel only in the
     * body; the answer can be several MB (FIT files), so it gets the longer sync timeout.
     */
    public JsonNode fetchDays(String tokens, java.util.List<java.time.LocalDate> days, int timeoutSeconds) {
        JsonNode body = post("/sync/days", Map.of("tokens", tokens, "dates", days.stream().map(Object::toString).toList()),
                timeoutSeconds);
        if (!body.hasNonNull("tokens") || !body.path("days").isArray()) throw new SidecarException("BAD_RESPONSE");
        return body;
    }

    public LoginResult login(String username, String password) {
        return loginResult(post("/auth/login", Map.of("username", username, "password", password)));
    }

    public LoginResult mfa(String mfaId, String code) {
        return loginResult(post("/auth/mfa", Map.of("mfaId", mfaId, "code", code)));
    }

    private LoginResult loginResult(JsonNode body) {
        String status = body.path("status").asText("");
        if ("CONNECTED".equals(status) && body.hasNonNull("tokens")) {
            return new LoginResult(status, body.get("tokens").asText(), null);
        }
        if ("MFA_REQUIRED".equals(status) && body.hasNonNull("mfaId")) {
            return new LoginResult(status, null, body.get("mfaId").asText());
        }
        throw new SidecarException("BAD_RESPONSE");
    }

    JsonNode post(String path, Object body) {
        return post(path, body, props.sidecarTimeoutSeconds());
    }

    JsonNode post(String path, Object body, int timeoutSeconds) {
        String secret = setup.syncSecret();
        if (secret == null) throw new SidecarException("NOT_CONFIGURED");
        HttpResponse<byte[]> res;
        try {
            var req = HttpRequest.newBuilder(URI.create(props.sidecarUrl() + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header(SECRET_HEADER, secret)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SidecarException("SIDECAR_UNREACHABLE");
        } catch (IOException | IllegalArgumentException e) {
            throw new SidecarException("SIDECAR_UNREACHABLE");
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(res.body());
        } catch (IOException e) {
            throw new SidecarException("BAD_RESPONSE");
        }
        if (res.statusCode() != 200) {
            String code = parsed == null ? "" : parsed.path("error").asText("");
            throw new SidecarException(code.matches("[A-Z_]{2,40}") ? code : "BAD_RESPONSE");
        }
        if (parsed == null || !parsed.isObject()) throw new SidecarException("BAD_RESPONSE");
        return parsed;
    }
}
