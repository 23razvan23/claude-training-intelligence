package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GoldenCliTest {
    @Test
    void parsesFreezeAndCheck() {
        var f = GoldenCli.parse(new String[] {"golden", "freeze", "--athlete", "1", "--from=2026-09-10", "--to", "2026-11-08", "--scores-only"});
        assertThat(f).isEqualTo(new GoldenCli.Options("freeze", 1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 11, 8), true, null));
        var c = GoldenCli.parse(new String[] {"golden", "check", "--athlete=7", "--file", "golden-athlete-7-x.json"});
        assertThat(c).isEqualTo(new GoldenCli.Options("check", 7, null, null, false, "golden-athlete-7-x.json"));
        assertThat(GoldenCli.parse(new String[] {"golden", "freeze", "--athlete", "1"}).from()).isNull();
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden"})).hasMessageContaining("freeze or check");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "wat", "--athlete", "1"})).hasMessageContaining("freeze or check");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "check"})).hasMessageContaining("--athlete");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "check", "--athlete", "1", "--from", "2026-01-01"})).hasMessageContaining("belongs to freeze");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "check", "--athlete", "1", "--scores-only"})).hasMessageContaining("belongs to freeze");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "freeze", "--athlete", "1", "--file", "x"})).hasMessageContaining("belongs to check");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "freeze", "--athlete", "x"})).hasMessageContaining("Bad value");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "freeze", "--athlete", "1", "--from"})).hasMessageContaining("Missing value");
        assertThatThrownBy(() -> GoldenCli.parse(new String[] {"golden", "freeze", "--athlete", "1", "--from", "2026-11-15", "--to", "2026-11-01"}))
                .hasMessageContaining("after");
    }

    @Test
    void badArgumentsExitWithUsageAndNeverStartTheApp() {
        var err = new ByteArrayOutputStream();
        int code = GoldenCli.run(new String[] {"golden", "check"}, args -> {
            throw new AssertionError("the application must not start");
        }, System.out, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(64);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage: golden freeze");
    }
}
