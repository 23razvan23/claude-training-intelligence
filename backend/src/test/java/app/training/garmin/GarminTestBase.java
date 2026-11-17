package app.training.garmin;

import app.training.IntegrationTest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared set-up for the Garmin integration tests: a random token key, a fake sync secret and one
 * fake sidecar, so every Garmin test class runs in the same application context. Fake values only.
 */
abstract class GarminTestBase extends IntegrationTest {
    static final FakeSidecar SIDECAR = new FakeSidecar();
    static final String KEY;

    static {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        KEY = Base64.getEncoder().encodeToString(k);
    }

    @DynamicPropertySource
    static void garmin(DynamicPropertyRegistry r) {
        r.add("app.garmin.token-key", () -> KEY);
        r.add("app.garmin.sync-secret", () -> "test-sync-secret");
        r.add("app.garmin.sidecar-url", SIDECAR::url);
        r.add("app.garmin.sidecar-timeout-seconds", () -> 5);
        r.add("app.garmin.sync.timeout-seconds", () -> 20);
    }
}
