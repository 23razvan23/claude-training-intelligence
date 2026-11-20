package app.training.adaptation.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import app.training.adaptation.signals.SignalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The backtest must replay a past morning only from rows that existed by that morning (stage 2-4, ADR 0023 D2).
 * Each test replays one morning, adds rows "from the future", replays it again and compares. The control test adds
 * the same kind of rows dated before the morning and checks that they <i>do</i> change it, so a filter that let
 * everything through, or nothing, would fail. Synthetic data only.
 */
class NoLookAheadIT extends IntegrationTest {
    @Autowired BacktestService backtest;
    @Autowired SignalService signals;
    @Autowired ObjectMapper json;

    /** The replayed morning: 30 days ago, a threshold day (index 89, day 5 of the week), with a red score. */
    static final int MORNING = 89;

    private BacktestSeed seed(String name) {
        long a = newAthlete(name);
        var s = new BacktestSeed(jdbc, a, signals.today(a)).history();
        s.score(MORNING - 1, 45, "YELLOW");
        s.score(MORNING, 25, "RED");
        return s;
    }

    private Replay.Morning replay(BacktestSeed s) {
        var p = BacktestParams.lastYear(signals.today(s.athlete));
        return backtest.replay(backtest.load(s.athlete), s.day(MORNING), p, backtest.settingsFor(p));
    }

    /** Everything a morning produces: the signals the rules read, the engine's inputs and every proposal, reason and note. */
    private JsonNode everything(Replay.Morning m) {
        ObjectNode n = signalsOnly(m);
        n.set("proposals", json.valueToTree(m.result().proposals()));
        n.set("health", json.valueToTree(m.result().healthMessages()));
        n.set("notes", json.valueToTree(m.notes()));
        return n;
    }

    /** The signals behind the morning, without the stand-in plan (which shows the day's own activity by design, Q1). */
    private ObjectNode signalsOnly(Replay.Morning m) {
        ObjectNode n = json.createObjectNode();
        ObjectNode today = m.today().deepCopy();
        today.remove("sessions");
        n.set("today", today);
        n.set("yesterday", m.yesterday());
        n.set("todayState", json.valueToTree(m.input().todayState()));
        n.set("yesterdayState", json.valueToTree(m.input().yesterdayState()));
        n.set("runKm", json.valueToTree(new TreeMap<>(m.input().runKmByDay())));
        n.set("illnessDays", json.valueToTree(new TreeSet<>(m.input().illnessDays())));
        return n;
    }

    private Timestamp cutoff(BacktestSeed s, long minutesOffset) {
        var zone = backtest.load(s.athlete).zone();
        return Timestamp.from(AsOf.cutoff(s.day(MORNING), BacktestParams.lastYear(s.day(MORNING)), zone).plusSeconds(minutesOffset * 60));
    }

    private void checkin(BacktestSeed s, LocalDate day, String extraColumns, String extraValues, Timestamp createdAt) {
        jdbc.sql("INSERT INTO subjective_log (athlete_id, log_date, created_at" + extraColumns + ") VALUES (?, ?, ?" + extraValues + ")")
                .params(s.athlete, day, createdAt).update();
    }

    @Test
    void rowsFromAfterTheMorningChangeNothing() {
        var s = seed("No look-ahead");
        var first = replay(s);
        var before = everything(first);
        var beforeSignals = signalsOnly(first);
        assertThat(first.result().proposals()).as("the red day should give the rules something to say").isNotEmpty();
        LocalDate morning = s.day(MORNING);
        Timestamp later = cutoff(s, 1);

        // Typed after the morning's cut-off: an illness for that very day, a niggle back-dated three days, a corrected
        // (then reverted) check-in, and a tolerance override.
        checkin(s, morning, ", illness", ", true", later);
        checkin(s, morning.minusDays(3), ", niggle_location, niggle_severity", ", 'calf', 3", later);
        jdbc.sql("INSERT INTO tolerance_override (athlete_id, km_per_week, note, created_at) VALUES (?, 5, 'later', ?)")
                .params(s.athlete, later).update();
        // Garmin RPE typed for activities of the morning's own day (after waking) and of a day past the plan window.
        long sameDay = s.activity(morning, 15, "cycling", "cycling", 30, 60, 0, 0, 80);
        long future = s.activity(s.day(MORNING + 16), 15, "running", "running", 10, 50, 0, 0, 90);
        for (long act : new long[] {sameDay, future}) {
            jdbc.sql("INSERT INTO subjective_log (athlete_id, log_date, activity_id, rpe, source, created_at) VALUES (?, ?, ?, 9, 'GARMIN_SEED', ?)")
                    .params(s.athlete, act == sameDay ? morning : s.day(MORNING + 16), act, cutoff(s, -24 * 60 * 40)).update();
        }
        // Beyond the plan's horizon: a huge block, worse scores, and different HRV and sleep on every later day.
        s.activity(s.day(MORNING + 20), 7, "running", "running", 40, 240, 30, 10, 900);
        jdbc.sql("UPDATE day_features SET day_load_edwards = day_load_edwards * 3 + 50, rmssd = 20, sleep_minutes = 200 WHERE athlete_id = ? AND cycle_date > ?")
                .params(s.athlete, morning.plusDays(15)).update();
        jdbc.sql("UPDATE derived_day_metrics SET recovery_pct = 5, recovery_band = 'RED', sleep_need_min = 600 WHERE athlete_id = ? AND cycle_date > ?")
                .params(s.athlete, morning).update();

        // The cycling on the morning's own day enters the stand-in plan (by design); everything else must be unchanged.
        var after = replay(s);
        assertThat(signalsOnly(after)).isEqualTo(beforeSignals);
        jdbc.sql("DELETE FROM activity WHERE id = ?").param(sameDay).update();
        assertThat(everything(replay(s))).isEqualTo(before);
    }

    @Test
    void theMorningsOwnTrainingIsNotYetDone() {
        var s = seed("Same day");
        var before = signalsOnly(replay(s));
        LocalDate morning = s.day(MORNING);
        // A second run that afternoon, and the day's total load as it stood that evening.
        s.activity(morning, 15, "running", "running", 25, 130, 40, 10, 400);
        jdbc.sql("UPDATE day_features SET day_load_edwards = 900, day_load_banister = 700, run_km = 37 WHERE athlete_id = ? AND cycle_date = ?")
                .params(s.athlete, morning).update();
        assertThat(signalsOnly(replay(s))).isEqualTo(before);
    }

    @Test
    void theSameRowsBeforeTheMorningDoChangeIt() {
        var s = seed("Control");
        var before = replay(s);
        assertThat(before.input().todayState().illness()).isFalse();
        Timestamp earlier = cutoff(s, -30);

        checkin(s, s.day(MORNING), ", illness", ", true", earlier);
        jdbc.sql("INSERT INTO tolerance_override (athlete_id, km_per_week, note, created_at) VALUES (?, 5, 'earlier', ?)")
                .params(s.athlete, earlier).update();
        var after = replay(s);
        assertThat(after.input().todayState().illness()).isTrue();
        assertThat(after.input().todayState().toleranceKmPerWeek()).isEqualTo(5.0);
        assertThat(after.result().proposals()).anySatisfy(p -> assertThat(p.rule()).isEqualTo("illness"));
        assertThat(everything(after)).isNotEqualTo(everything(before));
    }

    @Test
    void aCorrectionTypedLaterDoesNotReachBack() {
        var s = seed("Correction");
        checkin(s, s.day(MORNING), ", illness", ", true", cutoff(s, -60));
        checkin(s, s.day(MORNING), ", illness", ", false", cutoff(s, 60)); // "I wasn't ill after all", typed later
        assertThat(replay(s).input().todayState().illness()).isTrue();
    }

    @Test
    void withEveryRowKeptLatestWinsIsTheViewsRule() {
        var s = seed("View rule");
        LocalDate d = s.day(MORNING);
        checkin(s, d, ", rpe", ", 4", cutoff(s, -90));
        checkin(s, d, ", rpe", ", 6", cutoff(s, -90)); // same time: the higher id wins
        checkin(s, d.minusDays(1), ", soreness", ", 3", cutoff(s, -2000));
        checkin(s, d.minusDays(1), ", soreness", ", 2", cutoff(s, -3000)); // older: loses
        var mine = AsOf.current(backtest.load(s.athlete).checkins(), r -> true);
        var view = jdbc.sql("SELECT log_date, rpe, soreness FROM subjective_current WHERE athlete_id = ? ORDER BY log_date, created_at, id")
                .param(s.athlete).query((rs, n) -> rs.getObject("log_date", LocalDate.class) + "/" + rs.getObject("rpe") + "/" + rs.getObject("soreness"))
                .list();
        assertThat(mine.stream().map(c -> c.date() + "/" + c.rpe() + "/" + c.soreness()).toList()).isEqualTo(view);
    }
}
