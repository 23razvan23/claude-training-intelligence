package app.training.adaptation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.training.adaptation.engine.Model.Reason;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuggestCliTest {
    @Test
    void parsesTheAthlete() {
        assertThat(SuggestCli.parse(new String[] {"suggest", "--athlete", "3"})).isEqualTo(3);
        assertThat(SuggestCli.parse(new String[] {"suggest", "--athlete=4"})).isEqualTo(4);
    }

    @Test
    void badArgumentsExitWith64WithoutStartingTheApp() {
        assertThatThrownBy(() -> SuggestCli.parse(new String[] {"suggest"})).hasMessageContaining("required");
        assertThatThrownBy(() -> SuggestCli.parse(new String[] {"suggest", "--from", "x"})).hasMessageContaining("Unknown");
        var err = new ByteArrayOutputStream();
        int code = SuggestCli.run(new String[] {"suggest", "--athlete", "x"}, a -> {
            throw new AssertionError("must not start");
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(code).isEqualTo(64);
        assertThat(err.toString()).contains("Usage");
    }

    @Test
    void theSummarySaysWhatRanAndWhatIsPending() {
        var o = new SuggestionService.RunOutcome(1, LocalDate.of(2026, 11, 16), "r1", 3, "s3", 1, 0, 2, List.of(),
                List.of(new Reason("health.niggle", "The knee niggle has lasted 3 days.", Map.of())), List.of("A note."));
        assertThat(SuggestCli.summary(o)).isEqualTo("""
                Rules r1 (settings version 3, signals s3) on 2026-11-16: 1 new, 0 unchanged, 2 expired; 0 pending.
                Health: The knee niggle has lasted 3 days.
                Note: A note.
                """);
    }
}
