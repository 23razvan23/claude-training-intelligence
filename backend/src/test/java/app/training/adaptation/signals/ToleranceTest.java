package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Running tolerance (ADR 0021 D11) on synthetic weeks. Default settings unless a test says otherwise. */
class ToleranceTest {
    static final LocalDate D0 = LocalDate.of(2025, 1, 6); // a Monday
    static final Tolerance.Settings S = new Tolerance.Settings(365, 4, 0.5, 0.10, 1, 2, 1.0, 28);
    /** No chronic cap: a very large factor, to test peak and decay on their own. */
    static final Tolerance.Settings NO_CAP = new Tolerance.Settings(365, 4, 0.5, 0.10, 1, 2, 100.0, 28);

    /** Weekly km, spread evenly over the week's 7 days. */
    static List<Tolerance.Input> weeks(double... weeklyKm) {
        var out = new ArrayList<Tolerance.Input>();
        for (int w = 0; w < weeklyKm.length; w++)
            for (int d = 0; d < 7; d++) out.add(new Tolerance.Input(D0.plusDays(w * 7L + d), weeklyKm[w] / 7, true, false));
        return out;
    }

    static Tolerance.Day last(List<Tolerance.Input> days, Tolerance.Settings s) {
        var out = Tolerance.compute(days, s, Collections.nCopies(days.size(), null));
        return out.get(out.size() - 1);
    }

    @Test
    void thePeakIsTheBestFourWeekAverage() {
        var t = last(weeks(30, 40, 50, 60, 60, 40, 40, 40), NO_CAP);
        assertThat(t.peakKmPerWeek()).isEqualTo(52.5); // weeks 2-5 (index 1..4 → 40,50,60,60)
        assertThat(t.peakTo()).as("any window across weeks 1-5 averages 52.5").isBetween(D0.plusDays(34), D0.plusDays(41));
        assertThat(t.interruptedWeeks()).as("40 km is not under half of 52.5").isZero();
        assertThat(t.kmPerWeek()).isEqualTo(52.5);
        assertThat(t.source()).isEqualTo("computed");
    }

    @Test
    void aWindowWithANiggleOrInjuryDoesNotCount() {
        var days = weeks(30, 40, 50, 60, 60, 40, 40, 40);
        var flagged = new ArrayList<Tolerance.Input>();
        for (int i = 0; i < days.size(); i++) {
            var d = days.get(i);
            flagged.add(i == 4 * 7 ? new Tolerance.Input(d.date(), d.runKm(), true, true) : d);
        }
        var t = last(flagged, NO_CAP);
        // Every window holding day 28 (the first day of week 4) is out; the best left is weeks 0-3 (30,40,50,60) = 45.
        assertThat(t.peakKmPerWeek()).isEqualTo(45.0);
        assertThat(t.flaggedWindows()).isPositive();
    }

    @Test
    void timeAwayDecaysTenPercentPerInterruptedWeekBeyondTheFirst() {
        var t = last(weeks(50, 50, 50, 50, 10, 0, 0), NO_CAP);
        assertThat(t.peakKmPerWeek()).isEqualTo(50.0);
        assertThat(t.interruptedWeeks()).isEqualTo(3);
        assertThat(t.decayWeeks()).isEqualTo(2);
        assertThat(t.decayFactor()).isEqualTo(0.8);
        assertThat(t.kmPerWeek()).isEqualTo(40.0);
    }

    @Test
    void scatteredLowWeeksWhileTrainingSteadilyAreNotTimeAway() {
        // A 62 km block, then 36 weeks of steady but uneven training (~36 km/week, synthetic) in which some easier
        // weeks fall under half of the peak (31 km), and a normal last week. The athlete never stopped, so there is
        // no time away to decay for: the tolerance is the peak capped by the chronic volume, not a fraction of it.
        double[] cycle = {40, 28, 36, 45, 27, 38, 30, 44, 35};
        double[] w = new double[4 + 4 * cycle.length + 1];
        java.util.Arrays.fill(w, 0, 4, 62);
        for (int k = 0; k < 4 * cycle.length; k++) w[4 + k] = cycle[k % cycle.length];
        w[w.length - 1] = 42;
        var t = last(weeks(w), S);
        assertThat(t.peakKmPerWeek()).isEqualTo(62.0);
        assertThat(t.decayWeeks()).as("the last week is above half the peak: no current break").isZero();
        assertThat(t.capped()).isTrue();
        assertThat(t.kmPerWeek()).as("never below the chronic volume the athlete is running").isEqualTo(t.chronicKmPerWeek());
    }

    @Test
    void oneNormalWeekDoesNotEndABreakTwoInARowDo() {
        // A 50 km block, six weeks off, then a return. The decay is for the current break only.
        var oneBack = last(weeks(50, 50, 50, 50, 0, 0, 0, 0, 0, 0, 40), NO_CAP);
        assertThat(oneBack.interruptedWeeks()).as("one normal week: still the same break").isEqualTo(6);
        assertThat(oneBack.decayWeeks()).isEqualTo(5);
        assertThat(oneBack.kmPerWeek()).isEqualTo(25.0);
        var twoBack = last(weeks(50, 50, 50, 50, 0, 0, 0, 0, 0, 0, 40, 40), NO_CAP);
        assertThat(twoBack.interruptedWeeks()).as("two normal weeks in a row end the break").isZero();
        assertThat(twoBack.kmPerWeek()).isEqualTo(50.0);
        // After a return, the chronic cap holds the tolerance near what the athlete is running again.
        var capped = last(weeks(50, 50, 50, 50, 0, 0, 0, 0, 0, 0, 40, 40), S);
        assertThat(capped.capped()).isTrue();
        assertThat(capped.kmPerWeek()).isLessThan(40.0);
    }

    @Test
    void aSingleNormalWeekInsideABreakDoesNotResetIt() {
        // Off, one normal week, off again: both stretches belong to the current break (3 interrupted weeks).
        var t = last(weeks(50, 50, 50, 50, 40, 40, 0, 40, 0, 0), NO_CAP);
        assertThat(t.interruptedWeeks()).isEqualTo(3);
        assertThat(t.decayWeeks()).isEqualTo(2);
        assertThat(t.kmPerWeek()).isEqualTo(40.0);
    }

    @Test
    void theDecayStopsAtZero() {
        double[] w = new double[20];
        java.util.Arrays.fill(w, 0, 4, 50);
        assertThat(last(weeks(w), NO_CAP).kmPerWeek()).isEqualTo(0.0);
    }

    @Test
    void cappedByTheChronicRunningVolume() {
        // A big block long ago, then steady 32 km weeks: the chronic volume (~32/week) caps the 60 km peak.
        double[] w = new double[20];
        java.util.Arrays.fill(w, 0, 4, 60);
        java.util.Arrays.fill(w, 4, 20, 32);
        var t = last(weeks(w), S);
        assertThat(t.peakKmPerWeek()).isEqualTo(60.0);
        assertThat(t.interruptedWeeks()).as("32 is not under half of 60").isZero();
        assertThat(t.chronicKmPerWeek()).isEqualTo(32.0);
        assertThat(t.capped()).isTrue();
        assertThat(t.kmPerWeek()).isEqualTo(32.0);
        var roomier = last(weeks(w), new Tolerance.Settings(365, 4, 0.5, 0.10, 1, 2, 1.1, 28));
        assertThat(roomier.kmPerWeek()).isEqualTo(35.2);
    }

    @Test
    void onlyTheLastTwelveMonthsCount() {
        double[] w = new double[60];
        java.util.Arrays.fill(w, 0, 4, 80);
        java.util.Arrays.fill(w, 4, 60, 40);
        var t = last(weeks(w), NO_CAP);
        assertThat(t.peakKmPerWeek()).as("the 80 km block is more than a year back").isEqualTo(40.0);
    }

    @Test
    void theOverrideReplacesTheNumberAndTheComputedOneStaysVisible() {
        var days = weeks(50, 50, 50, 50);
        var ov = new Tolerance.OverrideValue(35.0, "calf history", days.get(20).date());
        var overrides = new ArrayList<Tolerance.OverrideValue>(Collections.nCopies(days.size(), null));
        for (int i = 20; i < days.size(); i++) overrides.set(i, ov);
        var out = Tolerance.compute(days, NO_CAP, overrides);
        assertThat(out.get(27).kmPerWeek()).isEqualTo(35.0);
        assertThat(out.get(27).source()).isEqualTo("override");
        assertThat(out.get(27).computedKmPerWeek()).isEqualTo(50.0);
        assertThat(out.get(27).override().note()).isEqualTo("calf history");
        assertThat(out.get(10).source()).as("before the override was set").isNotEqualTo("override");
    }

    @Test
    void noRunningMeansNoTolerance() {
        var t = last(weeks(0, 0, 0, 0, 0), S);
        assertThat(t.kmPerWeek()).isNull();
        assertThat(t.source()).isEqualTo("none");
    }
}
