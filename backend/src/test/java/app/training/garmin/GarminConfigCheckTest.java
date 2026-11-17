package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** ADR 0011 D10: each way the key or secret can be wrong disables sync, with a message that never shows the value. */
class GarminConfigCheckTest {
    private static String key(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void validKeyAndSecretEnableSync() {
        var r = GarminConfigCheck.check(key(32), "test-sync-secret");
        assertThat(r.enabled()).isTrue();
        assertThat(r.key()).hasSize(32);
        assertThat(r.toString()).doesNotContain("test-sync-secret");
    }

    @Test
    void surroundingWhitespaceInEnvIsTolerated() {
        assertThat(GarminConfigCheck.check(" " + key(32) + "\n", "s").enabled()).isTrue();
    }

    @Test
    void missingEmptyShortLongOrNotBase64KeyDisables() {
        String shortKey = key(16);
        String longKey = key(48);
        for (String bad : new String[] {null, "", "   ", shortKey, longKey, "not base64 at all!!", "%%%%"}) {
            var r = GarminConfigCheck.check(bad, "test-sync-secret");
            assertThat(r.enabled()).as(String.valueOf(bad)).isFalse();
            assertThat(r.key()).isNull();
            assertThat(r.reason()).isEqualTo(GarminConfigCheck.KEY_PROBLEM);
            if (bad != null && !bad.isBlank()) assertThat(r.reason()).doesNotContain(bad);
        }
    }

    @Test
    void missingSecretDisables() {
        for (String bad : new String[] {null, "", "  "}) {
            var r = GarminConfigCheck.check(key(32), bad);
            assertThat(r.enabled()).isFalse();
            assertThat(r.reason()).isEqualTo(GarminConfigCheck.SECRET_PROBLEM);
        }
    }
}
