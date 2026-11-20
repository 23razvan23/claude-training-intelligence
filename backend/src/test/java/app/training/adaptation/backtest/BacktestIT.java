package app.training.adaptation.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import app.training.adaptation.signals.SignalService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** A whole backtest run on synthetic data: stored under its run id, nothing live touched (ADR 0023 D5). */
class BacktestIT extends IntegrationTest {
    @Autowired BacktestService backtest;
    @Autowired SignalService signals;
    @Autowired ApplicationContext context;

    private long live(String table, long athlete) {
        return count("SELECT count(*) FROM " + table + " WHERE athlete_id = ?", athlete);
    }

    @Test
    void aRunIsStoredApartFromTheLiveTables() {
        long a = newAthlete("Backtest run");
        LocalDate today = signals.today(a);
        var s = new BacktestSeed(jdbc, a, today).history();
        for (int i = 80; i < 84; i++) s.score(i, 25, "RED");
        signals.recompute(a); // live signals exist, so we can check they are left alone
        long liveSignals = live("day_signals", a), liveSuggestions = live("adaptation_suggestion", a), livePlan = live("planned_session", a);

        var p = new BacktestParams(s.day(60), today.minusDays(1), null, List.of(s.day(90)), List.of(s.day(80)), null, null);
        var o = backtest.run(a, p, null);

        assertThat(o.mornings()).isEqualTo(BacktestSeed.DAYS - 61);
        assertThat(count("SELECT count(*) FROM backtest_day WHERE run_id = ?", o.runId())).isEqualTo(o.mornings());
        assertThat(count("SELECT count(*) FROM backtest_suggestion WHERE run_id = ?", o.runId())).isEqualTo(o.suggestions());
        assertThat(o.perRule()).containsKey("red");
        assertThat(o.firstReadinessMorning()).isEqualTo(s.day(60));
        assertThat(count("SELECT count(*) FROM backtest_suggestion WHERE run_id = ? AND activity_id IS NULL", o.runId())).isZero();
        // The races and hard weeks are kept with the run (the hard week as its Monday).
        assertThat(jdbc.sql("SELECT params -> 'races' ->> 0 FROM backtest_run WHERE id = ?").param(o.runId()).query(String.class).single())
                .isEqualTo(s.day(90).toString());
        assertThat(jdbc.sql("SELECT params -> 'hardWeeks' ->> 0 FROM backtest_run WHERE id = ?").param(o.runId()).query(String.class).single())
                .isEqualTo(s.day(80).with(java.time.DayOfWeek.MONDAY).toString());
        assertThat(jdbc.sql("SELECT summary ->> 'suggestions' FROM backtest_run WHERE id = ?").param(o.runId()).query(Integer.class).single())
                .isEqualTo(o.suggestions());

        assertThat(live("day_signals", a)).isEqualTo(liveSignals);
        assertThat(live("adaptation_suggestion", a)).isEqualTo(liveSuggestions);
        assertThat(live("planned_session", a)).isEqualTo(livePlan);
        assertThat(backtest.runs(a)).extracting(BacktestRepository.Run::id).containsExactly(o.runId());
    }

    @Test
    void marksAreAppendOnly() {
        long a = newAthlete("Backtest marks");
        LocalDate today = signals.today(a);
        var s = new BacktestSeed(jdbc, a, today).history();
        s.score(103, 25, "RED"); // a threshold day
        var o = backtest.run(a, new BacktestParams(s.day(95), s.day(105), null, null, null, null, null), null);
        long sug = jdbc.sql("SELECT min(id) FROM backtest_suggestion WHERE run_id = ?").param(o.runId()).query(Long.class).single();
        long mark = jdbc.sql("INSERT INTO backtest_mark (suggestion_id, verdict) VALUES (?, 'SENSIBLE') RETURNING id").param(sug).query(Long.class).single();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE backtest_mark SET verdict = 'UNSURE' WHERE id = ?").param(mark).update())
                .hasMessageContaining("append-only");
        // Deleting the athlete still removes everything (cascade).
        jdbc.sql("DELETE FROM athlete WHERE id = ?").param(a).update();
        assertThat(count("SELECT count(*) FROM backtest_mark WHERE id = ?", mark)).isZero();
    }

    @Test
    void theCommandRunsAndSaysNothingLiveChanged() {
        long a = newAthlete("Backtest command");
        var s = new BacktestSeed(jdbc, a, signals.today(a)).history();
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = BacktestCli.run(new String[] {"backtest", "--athlete", String.valueOf(a), "--from", s.day(100).toString()},
                args -> nonClosing(), new PrintStream(out), new PrintStream(err));
        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString()).contains("Backtest run").contains("Nothing in your plan or suggestions changed");
        assertThat(BacktestCli.run(new String[] {"backtest", "--athlete", "999999"}, args -> nonClosing(),
                new PrintStream(out), new PrintStream(err))).isEqualTo(2);
    }

    /** The shared test context, wrapped so the command's try-with-resources does not close it. */
    private ConfigurableApplicationContext nonClosing() {
        var real = (ConfigurableApplicationContext) context;
        return (ConfigurableApplicationContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ConfigurableApplicationContext.class}, (proxy, method, args) -> {
                    if ("close".equals(method.getName())) return null;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
