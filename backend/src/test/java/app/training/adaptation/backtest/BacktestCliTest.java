package app.training.adaptation.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class BacktestCliTest {
    @Test
    void parsesEveryOption() {
        var o = BacktestCli.parse(new String[] {"backtest", "--athlete", "1", "--from=2025-11-20", "--to", "2026-11-19", "--races",
                "2026-04-12, 2026-10-04", "--hard-weeks", "2026-02-11", "--cutoff", "08:30", "--settings-version", "4"});
        assertThat(o.athlete()).isEqualTo(1);
        assertThat(o.races()).containsExactly(LocalDate.parse("2026-04-12"), LocalDate.parse("2026-10-04"));
        var p = o.params(BacktestParams.lastYear(LocalDate.parse("2026-11-20")));
        assertThat(p.hardWeeks()).containsExactly(LocalDate.parse("2026-02-09")); // the Monday
        assertThat(p.morningCutoff()).isEqualTo(LocalTime.of(8, 30));
        assertThat(p.settingsVersion()).isEqualTo(4);
    }

    @Test
    void defaultsAreTheLastTwelveMonthsUpToYesterday() {
        var p = BacktestCli.parse(new String[] {"backtest", "--athlete", "1"}).params(BacktestParams.lastYear(LocalDate.parse("2026-11-20")));
        assertThat(p.from()).isEqualTo(LocalDate.parse("2025-11-20"));
        assertThat(p.to()).isEqualTo(LocalDate.parse("2026-11-19"));
        assertThat(p.morningCutoff()).isEqualTo(LocalTime.of(9, 0));
        var onlyTo = BacktestCli.parse(new String[] {"backtest", "--athlete", "1", "--to", "2026-06-30"})
                .params(BacktestParams.lastYear(LocalDate.parse("2026-11-20")));
        assertThat(onlyTo.from()).isEqualTo(LocalDate.parse("2025-07-01"));
    }

    @Test
    void badArgumentsExit64() {
        assertThatThrownBy(() -> BacktestCli.parse(new String[] {"backtest"})).hasMessageContaining("--athlete");
        assertThatThrownBy(() -> BacktestCli.parse(new String[] {"backtest", "--athlete", "1", "--from", "x"})).hasMessageContaining("--from");
        assertThatThrownBy(() -> BacktestCli.parse(new String[] {"backtest", "--athlete", "1", "--from", "2026-02-01", "--to", "2026-01-01"}))
                .hasMessageContaining("before");
        var err = new ByteArrayOutputStream();
        assertThat(BacktestCli.run(new String[] {"backtest", "--nope", "1"}, a -> null, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err))).isEqualTo(64);
        assertThat(err.toString()).contains("Usage");
    }
}
