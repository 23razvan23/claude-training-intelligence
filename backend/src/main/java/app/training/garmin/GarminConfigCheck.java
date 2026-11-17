package app.training.garmin;

import java.util.Base64;

/**
 * The startup check of ADR 0011 D10, as a pure function: is Garmin sync usable with these two
 * values? A missing or malformed value disables sync; the app still starts. The reason is a
 * fixed sentence that never contains either value.
 */
public final class GarminConfigCheck {
    public static final int KEY_BYTES = 32;

    static final String KEY_PROBLEM =
            "Garmin sync disabled: GARMIN_TOKEN_KEY missing or not 32 bytes (base64); see .env.example";
    static final String SECRET_PROBLEM = "Garmin sync disabled: GARMIN_SYNC_SECRET missing; see .env.example";

    /** {@code key} is set only when {@code enabled}; {@code reason} only when not. */
    public record Result(boolean enabled, byte[] key, String reason) {
        @Override
        public String toString() {
            return enabled ? "Result[enabled]" : "Result[disabled: " + reason + "]";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Result r && r.enabled == enabled && java.util.Objects.equals(r.reason, reason);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(enabled, reason);
        }
    }

    private GarminConfigCheck() {}

    public static Result check(String tokenKey, String syncSecret) {
        byte[] key = decodeKey(tokenKey);
        if (key == null) return new Result(false, null, KEY_PROBLEM);
        if (syncSecret == null || syncSecret.isBlank()) return new Result(false, null, SECRET_PROBLEM);
        return new Result(true, key, null);
    }

    private static byte[] decodeKey(String tokenKey) {
        if (tokenKey == null || tokenKey.isBlank()) return null;
        try {
            byte[] key = Base64.getDecoder().decode(tokenKey.strip());
            return key.length == KEY_BYTES ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
