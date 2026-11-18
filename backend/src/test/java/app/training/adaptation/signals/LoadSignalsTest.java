package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One test per formula of the load signals (stage 2-2, ADR 0021). Synthetic numbers only. */
class LoadSignalsTest {
    static final SignalSettings S = new SignalSettings("edwards", 7, 28, 7, 5, 7, 21);
    static final LocalDate D0 = LocalDate.of(2026, 1, 1);

    static List<LoadSignals.DayInput> days(Double[] total, Double[] run, double[] km) {
        var out = new ArrayList<LoadSignals.DayInput>();
        for (int i = 0; i < total.length; i++) {
            out.add(new LoadSignals.DayInput(D0.plusDays(i), total[i], run == null ? null : run[i], km == null ? 0 : km[i],
                    km == null ? 0 : km[i] * 5));
        }
        return out;
    }

    static Double[] constant(int n, double v) {
        Double[] a = new Double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    @Test
    void acuteChronicAndAcwrFollowTheEwma() {
        Double[] total = new Double[42];
        for (int i = 0; i < 42; i++) total[i] = i < 35 ? 50.0 : 150.0;
        var out = LoadSignals.compute(days(total, null, null), S);
        var last = out.get(41).load();
        Double[] acute = Ewma.of(total, 7);
        Double[] chronic = Ewma.of(total, 28);
        assertThat(last.acute()).isCloseTo(acute[41], within(0.05));
        assertThat(last.chronic()).isCloseTo(chronic[41], within(0.05));
        assertThat(last.acwr()).isCloseTo(acute[41] / chronic[41], within(0.001));
        assertThat(last.acwr()).as("a hard week after five steady ones").isGreaterThan(1.3);
        assertThat(out.get(20).load().chronic()).as("chronic needs 28 known days").isNull();
        assertThat(out.get(34).load().acwr()).as("steady load").isCloseTo(1.0, within(0.001));
    }

    @Test
    void runningLoadIsZeroOnAKnownDayWithoutARunAndUnknownOnAnUnknownDay() {
        Double[] total = {40.0, 40.0, null};
        Double[] run = {30.0, null, null};
        var out = LoadSignals.compute(days(total, run, null), S);
        assertThat(out.get(0).load().running().daily()).isEqualTo(30.0);
        assertThat(out.get(1).load().running().daily()).isEqualTo(0.0);
        assertThat(out.get(2).load().running().daily()).isNull();
        assertThat(out.get(2).load().daily()).isNull();
    }

    @Test
    void fosterMonotonyAndStrainOverTheWeek() {
        Double[] total = {100.0, 0.0, 100.0, 0.0, 100.0, 0.0, 100.0};
        var f = LoadSignals.compute(days(total, null, null), S).get(6).foster();
        // mean 400/7, population SD = sqrt(mean of squared deviations)
        double mean = 400.0 / 7;
        double sd = Math.sqrt((4 * Math.pow(100 - mean, 2) + 3 * Math.pow(mean, 2)) / 7);
        assertThat(f.knownDays()).isEqualTo(7);
        assertThat(f.weekLoad()).isEqualTo(400.0);
        assertThat(f.monotony()).isCloseTo(mean / sd, within(0.001));
        assertThat(f.strain()).isCloseTo(400 * mean / sd, within(0.1));
    }

    @Test
    void sameLoadEveryDayHasNoMonotonyRatherThanInfinity() {
        var f = LoadSignals.compute(days(constant(7, 60.0), null, null), S).get(6).foster();
        assertThat(f.sd()).isEqualTo(0.0);
        assertThat(f.monotony()).isNull();
        assertThat(f.strain()).isNull();
    }

    @Test
    void tooFewRecordedDaysGiveNoMonotony() {
        Double[] total = {100.0, null, null, null, 20.0, 80.0, 40.0};
        var f = LoadSignals.compute(days(total, null, null), S).get(6).foster();
        assertThat(f.knownDays()).isEqualTo(4);
        assertThat(f.weekLoad()).isEqualTo(240.0);
        assertThat(f.monotony()).isNull();
    }

    @Test
    void runningVolumeSumsTheShortAndLongWindows() {
        int n = 30;
        double[] km = new double[n];
        for (int i = 0; i < n; i++) km[i] = i % 2 == 0 ? 10 : 0;
        var r = LoadSignals.compute(days(constant(n, 30.0), null, km), S).get(n - 1).running();
        // last 7 days: indices 23..29 → even ones 24, 26, 28 = 30 km; last 21: 9..29 → 10 even = 100 km
        assertThat(r.shortKm()).isEqualTo(30.0);
        assertThat(r.shortMinutes()).isEqualTo(150.0);
        assertThat(r.longKm()).isEqualTo(100.0);
        assertThat(r.longWeeklyAvgKm()).isCloseTo(100.0 / 3, within(0.01));
    }

    @Test
    void refusesAGapInTheDateGrid() {
        var list = new ArrayList<>(days(constant(3, 1.0), null, null));
        list.remove(1);
        assertThatThrownBy(() -> LoadSignals.compute(list, S)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsAreChecked() {
        assertThatThrownBy(() -> new SignalSettings("strain", 7, 28, 7, 5, 7, 21)).hasMessageContaining("edwards");
        assertThatThrownBy(() -> new SignalSettings("edwards", 7, 28, 7, 8, 7, 21)).isInstanceOf(IllegalArgumentException.class);
    }
}
