package app.training.garmin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Garmin sync settings (ADR 0011). The two secrets come from .env; neither is ever logged or
 * returned by an endpoint, so {@link #toString()} hides them.
 *
 * @param tokenKey base64 of 32 random bytes that encrypt the Garmin token (GARMIN_TOKEN_KEY)
 * @param syncSecret the shared secret sent to the sidecar as X-Sync-Secret (GARMIN_SYNC_SECRET)
 * @param sidecarUrl where the garmin-sync sidecar listens on the Compose network
 * @param sidecarTimeoutSeconds how long to wait for the sidecar; a login tries several Garmin sign-in routes
 */
@ConfigurationProperties("app.garmin")
public record GarminProperties(String tokenKey, String syncSecret, String sidecarUrl, int sidecarTimeoutSeconds) {

    @Override
    public String toString() {
        return "GarminProperties[tokenKey=***, syncSecret=***, sidecarUrl=" + sidecarUrl
                + ", sidecarTimeoutSeconds=" + sidecarTimeoutSeconds + "]";
    }
}
