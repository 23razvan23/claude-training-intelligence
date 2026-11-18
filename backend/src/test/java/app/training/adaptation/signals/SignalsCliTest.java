package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class SignalsCliTest {
    @Test
    void parsesTheAthlete() {
        assertThat(SignalsCli.parse(new String[] {"signals", "--athlete", "3"})).isEqualTo(3);
        assertThat(SignalsCli.parse(new String[] {"signals", "--athlete=4"})).isEqualTo(4);
    }

    @Test
    void badArgumentsExitWith64WithoutStartingTheApp() {
        assertThatThrownBy(() -> SignalsCli.parse(new String[] {"signals"})).hasMessageContaining("required");
        assertThatThrownBy(() -> SignalsCli.parse(new String[] {"signals", "--from", "x"})).hasMessageContaining("Unknown");
        var err = new ByteArrayOutputStream();
        int code = SignalsCli.run(new String[] {"signals", "--athlete", "x"}, a -> {
            throw new AssertionError("must not start");
        }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(code).isEqualTo(64);
        assertThat(err.toString()).contains("Usage");
    }
}
