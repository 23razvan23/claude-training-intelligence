package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScoringCliTest {
    @Test
    void parsesBothOptionStyles() {
        var o = ScoringCli.parse(new String[] {"score", "--athlete", "1", "--from=2026-11-01", "--to", "2026-11-15"});
        assertThat(o).isEqualTo(new ScoringCli.Options(1, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15)));
        assertThat(ScoringCli.parse(new String[] {"score", "--athlete=7"})).isEqualTo(new ScoringCli.Options(7, null, null));
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> ScoringCli.parse(new String[] {"score"})).hasMessageContaining("--athlete");
        assertThatThrownBy(() -> ScoringCli.parse(new String[] {"score", "--athlete", "x"})).hasMessageContaining("Bad value");
        assertThatThrownBy(() -> ScoringCli.parse(new String[] {"score", "--athlete", "1", "--from"})).hasMessageContaining("Missing value");
        assertThatThrownBy(() -> ScoringCli.parse(new String[] {"score", "--athlete", "1", "--wat", "2"})).hasMessageContaining("Unknown option");
        assertThatThrownBy(() -> ScoringCli.parse(new String[] {"score", "--athlete", "1", "--from", "2026-11-15", "--to", "2026-11-01"}))
                .hasMessageContaining("after");
    }

    @Test
    void badArgumentsExitWithUsageAndNeverStartTheApp() {
        var err = new ByteArrayOutputStream();
        int code = ScoringCli.run(new String[] {"score"}, args -> {
            throw new AssertionError("the application must not start");
        }, System.out, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(64);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(ScoringCli.USAGE);
    }
}
