package app.training.garmin;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads GARMIN_TOKEN_KEY and GARMIN_SYNC_SECRET once at startup (ADR 0011 D10). If either is
 * missing or malformed, one warning is logged (never the value) and Garmin sync is disabled;
 * everything else in the app works as before.
 */
@Component
public class GarminSetup {
    private static final Logger log = LoggerFactory.getLogger(GarminSetup.class);

    private final TokenCipher cipher;
    private final String syncSecret;
    private final String disabledReason;

    public GarminSetup(GarminProperties props) {
        var result = GarminConfigCheck.check(props.tokenKey(), props.syncSecret());
        if (result.enabled()) {
            this.cipher = new TokenCipher(result.key());
            this.syncSecret = props.syncSecret();
            this.disabledReason = null;
            log.info("Garmin sync configured (token key id {})", cipher.keyId());
        } else {
            this.cipher = null;
            this.syncSecret = null;
            this.disabledReason = result.reason();
            log.warn(result.reason());
        }
    }

    public boolean enabled() {
        return cipher != null;
    }

    public Optional<TokenCipher> cipher() {
        return Optional.ofNullable(cipher);
    }

    /** Only for the sidecar client's header. */
    String syncSecret() {
        return syncSecret;
    }

    public String disabledReason() {
        return disabledReason;
    }

    @Override
    public String toString() {
        return "GarminSetup[enabled=" + enabled() + "]";
    }
}
