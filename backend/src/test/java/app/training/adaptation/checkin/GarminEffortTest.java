package app.training.adaptation.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The mapping confirmed on the coach's real values (ADR 0020 amendment) and the command's arguments. */
class GarminEffortTest {
    @Test
    void everyValueSeenOnTheRealExportMapsToItsTenthPart() {
        int[] seen = {10, 20, 30, 40, 50, 60};
        for (int v : seen) assertThat(GarminEffort.rpe(v, 10)).isEqualTo(v / 10);
    }

    @Test
    void theEdgesOfTheScale() {
        assertThat(GarminEffort.rpe(100, 10)).isEqualTo(10);
        assertThat(GarminEffort.rpe(10, 10)).isEqualTo(1);
    }

    @Test
    void emptyZeroAndNegativeMeanNotSet() {
        assertThat(GarminEffort.rpe(null, 10)).isNull();
        assertThat(GarminEffort.rpe(0, 10)).isNull();
        assertThat(GarminEffort.rpe(-10, 10)).isNull();
    }

    @Test
    void aValueOffTheStepIsRoundedAndClampedToTheCheckinRange() {
        assertThat(GarminEffort.rpe(35, 10)).isEqualTo(4);   // half up
        assertThat(GarminEffort.rpe(34, 10)).isEqualTo(3);
        assertThat(GarminEffort.rpe(4, 10)).isEqualTo(1);    // never 0: that would mean "not set"
        assertThat(GarminEffort.rpe(250, 10)).isEqualTo(10);
    }

    @Test
    void theScaleFactorIsConfigurable() {
        assertThat(GarminEffort.rpe(50, 5)).isEqualTo(10);
        assertThat(GarminEffort.rpe(50, 0)).isNull();
    }

    @Test
    void parsesArgumentsAndRejectsBadOnes() {
        assertThat(GarminSeedCli.parse(new String[] {"garmin-seed", "--athlete", "1"})).isEqualTo(1);
        assertThat(GarminSeedCli.parse(new String[] {"garmin-seed", "--athlete=7"})).isEqualTo(7);
        assertThatThrownBy(() -> GarminSeedCli.parse(new String[] {"garmin-seed"})).hasMessageContaining("--athlete");
        assertThatThrownBy(() -> GarminSeedCli.parse(new String[] {"garmin-seed", "--athlete", "x"})).hasMessageContaining("Bad value");
        assertThatThrownBy(() -> GarminSeedCli.parse(new String[] {"garmin-seed", "--wat", "1"})).hasMessageContaining("Unknown option");
        assertThatThrownBy(() -> GarminSeedCli.parse(new String[] {"garmin-seed", "--athlete"})).hasMessageContaining("Missing value");
    }

    @Test
    void badArgumentsExitWithUsageAndNeverStartTheApp() {
        var err = new ByteArrayOutputStream();
        int code = GarminSeedCli.run(new String[] {"garmin-seed"}, args -> {
            throw new AssertionError("the application must not start");
        }, System.out, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(64);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(GarminSeedCli.USAGE);
    }
}
