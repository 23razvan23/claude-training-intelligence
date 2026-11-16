package app.training.metrics.holdout;

import static app.training.metrics.holdout.HoldoutEvaluator.COMPARED;
import static app.training.metrics.holdout.HoldoutEvaluator.COMPARED_LOW_COVERAGE;
import static app.training.metrics.holdout.HoldoutEvaluator.NO_SCORE_YET;
import static app.training.metrics.holdout.HoldoutEvaluator.NO_WHOOP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import app.training.metrics.holdout.HoldoutEvaluator.Input;
import app.training.metrics.holdout.HoldoutEvaluator.Marks;
import app.training.metrics.holdout.HoldoutEvaluator.OurDay;
import app.training.metrics.holdout.HoldoutEvaluator.Overall;
import app.training.metrics.holdout.HoldoutEvaluator.Phase;
import app.training.metrics.holdout.HoldoutEvaluator.Report;
import app.training.metrics.holdout.HoldoutEvaluator.State;
import app.training.metrics.holdout.HoldoutEvaluator.WhoopDay;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * ADR 0009 rules H2–H8 on synthetic days. Bands use 67/33; the window starts on Monday
 * 2026-11-09 (as for m15); Whoop ends 2027-02-19.
 */
class HoldoutEvaluatorTest {
    static final LocalDate FROM = LocalDate.parse("2026-11-09");
    static final LocalDate WHOOP_END = LocalDate.parse("2027-02-19");
    static final LocalDate RUNNING = LocalDate.parse("2026-11-16");
    static final ObjectMapper JSON = new ObjectMapper();

    static Marks marks() {
        return new Marks(0.70, 0.03, 2.0, 0.80, 600, 90, 0.95, "wilson", FROM, "ADR 0009", "V15");
    }

    /** A plain day where both sides agree well: recovery r, strain s with full coverage, sleep performance 85. */
    static OurDay ours(LocalDate d, double r, double s) {
        return new OurDay(d, r, (int) Math.round(r), HoldoutEvaluator.band(r, 67, 33), null, false, s, s, null, 900.0, 85.0, 85,
                null, 480.0, false, null, false);
    }

    static WhoopDay whoop(LocalDate d, Integer r, Double s) {
        return new WhoopDay(d, r, s, 80, 470);
    }

    /** n days from {@code start}, each with ours and Whoop's recovery 75 (green) and strain varying 8..14 (ours +0.5). */
    static List<OurDay> oursDays(LocalDate start, int n) {
        var out = new ArrayList<OurDay>();
        for (int i = 0; i < n; i++) out.add(ours(start.plusDays(i), 75, 8 + i % 7 + 0.5));
        return out;
    }

    static List<WhoopDay> whoopDays(LocalDate start, int n) {
        var out = new ArrayList<WhoopDay>();
        for (int i = 0; i < n; i++) out.add(whoop(start.plusDays(i), 75, 8.0 + i % 7));
        return out;
    }

    static Input input(List<OurDay> ours, List<WhoopDay> whoop, LocalDate today, String status) {
        return new Input("m15", status, Instant.parse("2026-11-15T19:47:54Z"), null, JSON.createObjectNode(), marks(), 67, 33,
                ours, whoop, WHOOP_END, today, 28, List.of());
    }

    static Report run(List<OurDay> ours, List<WhoopDay> whoop) {
        return HoldoutEvaluator.evaluate(input(ours, whoop, RUNNING, "ACTIVE"));
    }

    static List<OurDay> replace(List<OurDay> days, LocalDate d, UnaryOperator<OurDay> f) {
        return days.stream().map(o -> o.date().equals(d) ? f.apply(o) : o).toList();
    }

    static OurDay noRecovery(OurDay o, String reason) {
        return new OurDay(o.date(), null, null, null, reason, false, o.strain(), o.strainShown(), o.strainReason(), o.monitoredMinutes(),
                o.sleepPerf(), o.sleepPerfPct(), o.sleepPerfReason(), o.sleepNeed(), false, null, false);
    }

    static OurDay withRecovery(OurDay o, double r, boolean lowerConfidence) {
        return new OurDay(o.date(), r, (int) Math.round(r), HoldoutEvaluator.band(r, 67, 33), null, lowerConfidence, o.strain(),
                o.strainShown(), o.strainReason(), o.monitoredMinutes(), o.sleepPerf(), o.sleepPerfPct(), o.sleepPerfReason(),
                o.sleepNeed(), false, null, false);
    }

    static OurDay withStrain(OurDay o, Double s, String reason, Double monitored) {
        return new OurDay(o.date(), o.recovery(), o.recoveryPct(), o.band(), o.recoveryReason(), o.lowerConfidence(), s, s, reason,
                monitored, o.sleepPerf(), o.sleepPerfPct(), o.sleepPerfReason(), o.sleepNeed(), false, null, false);
    }

    static OurDay noSleep(OurDay o) {
        return new OurDay(o.date(), o.recovery(), o.recoveryPct(), o.band(), o.recoveryReason(), o.lowerConfidence(), o.strain(),
                o.strainShown(), o.strainReason(), o.monitoredMinutes(), null, null, "NO_SLEEP", o.sleepNeed(), false, null, false);
    }

    // ---------------------------------------------------------------- H2 window

    @Test
    void windowRunsFromTheFrozenDateToTheLastFinishedWhoopDay() {
        // Our scores and Whoop both start before the window (the test window): those days are never compared.
        var r = run(oursDays(FROM.minusDays(10), 22), whoopDays(FROM.minusDays(10), 20));
        assertThat(r.windowFrom()).isEqualTo(FROM);
        assertThat(r.windowTo()).isEqualTo(FROM.plusDays(9));
        assertThat(r.days()).hasSize(10);
        assertThat(r.days().getFirst().date()).isEqualTo(FROM);
        assertThat(r.cumulative().recovery().n()).isEqualTo(10);
        // Our two later days have no Whoop cycle yet (e.g. today's, unfinished, never imported): not in the window.
        assertThat(r.lastScoredDate()).isEqualTo(FROM.plusDays(11));
    }

    @Test
    void noWhoopDayInTheWindowYetMeansAnEmptyComparison() {
        var r = run(oursDays(FROM.minusDays(5), 10), whoopDays(FROM.minusDays(5), 5));
        assertThat(r.windowTo()).isNull();
        assertThat(r.days()).isEmpty();
        assertThat(r.cumulative().recovery().n()).isZero();
        assertThat(r.cumulative().recovery().bandAgreement().value()).isNull();
        assertThat(r.rolling()).isNull();
        assertThat(r.weeks()).isEmpty();
        assertThat(r.verdict().state()).isEqualTo(State.NOT_ENOUGH_DAYS_YET);
    }

    @Test
    void theWindowStartIsReadFromTheFrozenMarksOnly() {
        var json = JSON.createObjectNode()
                .put("recovery_band_agreement_min", 0.7).put("recovery_green_red_max", 0.03).put("strain_mae_max", 2.0)
                .put("strain_spearman_min", 0.8).put("strain_min_monitored_minutes", 600).put("min_recovery_days", 90)
                .put("confidence", 0.95).put("interval", "wilson").put("window_from", "2026-11-09").put("protocol", "ADR 0009")
                .put("source", "V15");
        var m = Marks.of(json);
        assertThat(m).isEqualTo(marks());
        json.remove("strain_mae_max");
        assertThatThrownBy(() -> Marks.of(json)).hasMessageContaining("strain_mae_max");
        assertThatThrownBy(() -> Marks.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- H4 every day counted once

    @Test
    void everyDayOfTheWindowIsCountedOnceInExactlyOneGroupPerScore() {
        var ours = oursDays(FROM, 18);           // our last data date: FROM+17
        var whoop = new ArrayList<>(whoopDays(FROM, 20));
        whoop.removeIf(w -> w.date().equals(FROM.plusDays(2)));           // no Whoop cycle at all
        whoop.replaceAll(w -> w.date().equals(FROM.plusDays(3)) ? new WhoopDay(w.date(), null, null, null, null) : w); // cycle without scores
        ours = replace(ours, FROM.plusDays(4), o -> noRecovery(o, "NO_HRV"));
        ours = replace(ours, FROM.plusDays(5), o -> noRecovery(o, "NO_STRAIN_YESTERDAY/MISSING_WORKOUT_HR"));
        ours = replace(ours, FROM.plusDays(6), o -> withStrain(o, null, "NO_ALL_DAY_HR", null));
        ours = replace(ours, FROM.plusDays(7), o -> withStrain(o, 10.0, null, 300.0));
        ours = replace(ours, FROM.plusDays(8), HoldoutEvaluatorTest::noSleep);
        // Our score is missing on a day Whoop has (a gap in our rows inside the data range is impossible
        // by ADR 0012 D4, but a day after our last data date is not): FROM+18 and FROM+19.

        var r = run(ours, whoop);
        assertThat(r.days()).hasSize(20);
        var g = r.cumulative().groups();
        for (var e : g.entrySet()) {
            assertThat(e.getValue().values().stream().mapToInt(Integer::intValue).sum()).as(e.getKey()).isEqualTo(20);
        }
        assertThat(g.get("recovery")).isEqualTo(Map.of(COMPARED, 14, NO_WHOOP, 2, NO_SCORE_YET, 2, "NO_HRV", 1,
                "NO_STRAIN_YESTERDAY/MISSING_WORKOUT_HR", 1));
        assertThat(g.get("strain")).isEqualTo(Map.of(COMPARED, 14, COMPARED_LOW_COVERAGE, 1, NO_WHOOP, 2, NO_SCORE_YET, 2,
                "NO_ALL_DAY_HR", 1));
        assertThat(g.get("sleepPerformance")).isEqualTo(Map.of(COMPARED, 15, NO_WHOOP, 2, NO_SCORE_YET, 2, "NO_SLEEP", 1));
        assertThat(r.cumulative().recovery().n()).isEqualTo(14);
        assertThat(r.cumulative().strain().n()).isEqualTo(14);
        assertThat(r.cumulative().strainLowCoverage().n()).isEqualTo(1);

        // The day table carries the same group as the counts.
        assertThat(r.days().get(2).recoveryGroup()).isEqualTo(NO_WHOOP);
        assertThat(r.days().get(4).recoveryGroup()).isEqualTo("NO_HRV");
        assertThat(r.days().get(7).lowCoverage()).isTrue();
        assertThat(r.days().get(19).recoveryGroup()).isEqualTo(NO_SCORE_YET);
    }

    @Test
    void noWhoopTakesPrecedenceOverOurOwnReason() {
        var ours = replace(oursDays(FROM, 3), FROM.plusDays(1), o -> noRecovery(o, "NO_HRV"));
        var whoop = new ArrayList<>(whoopDays(FROM, 3));
        whoop.removeIf(w -> w.date().equals(FROM.plusDays(1)));
        var r = run(ours, whoop);
        assertThat(r.cumulative().groups().get("recovery")).isEqualTo(Map.of(COMPARED, 2, NO_WHOOP, 1));
    }

    @Test
    void lowerConfidenceRecoveryIsComparedAndCounted() {
        var ours = replace(oursDays(FROM, 5), FROM.plusDays(1), o -> withRecovery(o, 20, true)); // also a green-vs-red flip
        var r = run(ours, whoopDays(FROM, 5));
        assertThat(r.cumulative().recovery().n()).isEqualTo(5);
        assertThat(r.cumulative().recovery().lowerConfidence()).isEqualTo(1);
        assertThat(r.cumulative().recovery().greenRed().k()).isEqualTo(1);
        assertThat(r.days().get(1).lowerConfidence()).isTrue();
    }

    @Test
    void lowCoverageStrainIsShownApartAndNeverJudged() {
        var ours = oursDays(FROM, 10);
        // A wildly wrong strain on a low-coverage day: it must not touch the strain marks.
        ours = replace(ours, FROM.plusDays(3), o -> withStrain(o, 21.0, null, 120.0));
        var r = run(ours, whoopDays(FROM, 10));
        assertThat(r.cumulative().strain().n()).isEqualTo(9);
        assertThat(r.cumulative().strain().mae().value()).isCloseTo(0.5, within(1e-9));
        assertThat(r.cumulative().strainLowCoverage().n()).isEqualTo(1);
        assertThat(r.cumulative().strainLowCoverage().mae().value()).isCloseTo(21.0 - 11.0, within(1e-9));
    }

    @Test
    void lowCoverageUsesTheFrozenMinutesAndTreatsUnknownMinutesAsNone() {
        var ours = oursDays(FROM, 3);
        ours = replace(ours, FROM, o -> withStrain(o, 9.0, null, 600.0));       // exactly at the mark: full coverage
        ours = replace(ours, FROM.plusDays(1), o -> withStrain(o, 9.0, null, 599.0));
        ours = replace(ours, FROM.plusDays(2), o -> withStrain(o, 9.0, null, null));
        var g = run(ours, whoopDays(FROM, 3)).cumulative().groups().get("strain");
        assertThat(g).isEqualTo(Map.of(COMPARED, 1, COMPARED_LOW_COVERAGE, 2));
    }

    // ---------------------------------------------------------------- H5 rates and intervals

    @Test
    void ratesFollowTheCalibrationDefinitionsWithWilsonIntervals() {
        // 10 days, Whoop always green (75). Ours: 7 green, 2 yellow, 1 red.
        var ours = oursDays(FROM, 10);
        ours = replace(ours, FROM.plusDays(1), o -> withRecovery(o, 50, false));
        ours = replace(ours, FROM.plusDays(2), o -> withRecovery(o, 66.9, false)); // just below green: yellow (unrounded band)
        ours = replace(ours, FROM.plusDays(3), o -> withRecovery(o, 33, false));   // red_max is inclusive
        var r = run(ours, whoopDays(FROM, 10)).cumulative().recovery();
        assertThat(r.n()).isEqualTo(10);
        assertThat(r.bandAgreement()).isEqualTo(Wilson.rate(7, 10, 0.95));
        assertThat(r.greenRed()).isEqualTo(Wilson.rate(1, 10, 0.95));
        assertThat(r.confusion().get("GREEN")).isEqualTo(Map.of("GREEN", 7, "YELLOW", 2, "RED", 1));
        assertThat(r.mae().n()).isEqualTo(10);
        assertThat(r.mae().value()).isCloseTo((25 + 8.1 + 42) / 10.0, within(1e-9));
    }

    @Test
    void spearmanAndMaeMatchScipyOnATiedExample() {
        // Average ranks [1, 2.5, 2.5, 4, 5] and [2, 1, 3.5, 3.5, 5]: Pearson of the ranks = 7.25 / 9.5 = 29/38,
        // which is what scipy.stats.spearmanr([1, 2, 2, 3, 5], [2, 1, 4, 4, 6]) gives.
        var s = HoldoutEvaluator.spearman(new double[] {1, 2, 2, 3, 5}, new double[] {2, 1, 4, 4, 6});
        assertThat(s.n()).isEqualTo(5);
        assertThat(s.value()).isCloseTo(29.0 / 38.0, within(1e-12));
        assertThat(HoldoutEvaluator.spearman(new double[] {1, 2}, new double[] {1, 2}).value()).isNull();
        assertThat(HoldoutEvaluator.spearman(new double[] {1, 1, 1}, new double[] {1, 2, 3}).value()).isNull();
        assertThat(HoldoutEvaluator.mae(new double[] {1, 5}, new double[] {2, 2}).value()).isEqualTo(2.0);
    }

    @Test
    void rollingAndWeeklyFiguresCoverTheEndOfTheWindowAndAddUp() {
        int n = 40;
        var r = run(oursDays(FROM, n), whoopDays(FROM, n));
        assertThat(r.rolling().from()).isEqualTo(FROM.plusDays(n - 28));
        assertThat(r.rolling().to()).isEqualTo(FROM.plusDays(n - 1));
        assertThat(r.rolling().recovery().n()).isEqualTo(28);
        // Weeks run Monday to Sunday; 2026-11-09 is a Monday, so 40 days = 5 full weeks + 5 days.
        assertThat(r.weeks()).hasSize(6);
        assertThat(r.weeks().getFirst().from()).isEqualTo(FROM);
        assertThat(r.weeks().getFirst().to()).isEqualTo(FROM.plusDays(6));
        assertThat(r.weeks().getLast().days()).isEqualTo(5);
        assertThat(r.weeks().stream().mapToInt(w -> w.recovery().n()).sum()).isEqualTo(r.cumulative().recovery().n());
    }

    @Test
    void aShortWindowGivesARollingPeriodFromTheWindowStart() {
        var r = run(oursDays(FROM, 5), whoopDays(FROM, 5));
        assertThat(r.rolling().from()).isEqualTo(FROM);
        assertThat(r.rolling().recovery().n()).isEqualTo(5);
    }

    // ---------------------------------------------------------------- H6 verdict

    @Test
    void belowTheMinimumNoMarkIsJudged() {
        var r = run(oursDays(FROM, 89), whoopDays(FROM, 89));
        assertThat(r.phase()).isEqualTo(Phase.RUNNING);
        assertThat(r.verdict().state()).isEqualTo(State.NOT_ENOUGH_DAYS_YET);
        assertThat(r.verdict().overall()).isNull();
        assertThat(r.verdict().recoveryDays()).isEqualTo(89);
        assertThat(r.verdict().minRecoveryDays()).isEqualTo(90);
        assertThat(r.verdict().marks()).allSatisfy(m -> assertThat(m.met()).isNull());
        // The numbers and intervals are still shown.
        assertThat(r.verdict().marks().getFirst().rate()).isEqualTo(Wilson.rate(89, 89, 0.95));
    }

    @Test
    void fromTheMinimumOnAVerdictBeforeTheLastWhoopDayIsInterim() {
        var r = run(oursDays(FROM, 90), whoopDays(FROM, 90));
        assertThat(r.verdict().state()).isEqualTo(State.INTERIM);
        assertThat(r.verdict().overall()).isEqualTo(Overall.PASS);
        assertThat(r.verdict().marks()).allSatisfy(m -> assertThat(m.met()).isTrue());
        var strainMae = r.verdict().marks().get(2);
        assertThat(strainMae.key()).isEqualTo("strain_mae_max");
        assertThat(strainMae.value()).isCloseTo(0.5, within(1e-9));
        assertThat(strainMae.n()).isEqualTo(90);
    }

    @Test
    void theMinimumCountsRecoveryDaysOnlyAndStrainIsJudgedOnWhatItHas() {
        // 90 recovery days, but only 30 full-coverage strain days (A1).
        var ours = oursDays(FROM, 90);
        for (int i = 30; i < 90; i++) ours = replace(ours, FROM.plusDays(i), o -> withStrain(o, o.strain(), null, 100.0));
        var r = run(ours, whoopDays(FROM, 90));
        assertThat(r.verdict().state()).isEqualTo(State.INTERIM);
        assertThat(r.verdict().marks().get(2).n()).isEqualTo(30);
        assertThat(r.verdict().marks().get(3).n()).isEqualTo(30);
    }

    @Test
    void aMarkIsMetOnItsMeasuredValueEvenWhenTheIntervalCrossesIt() {
        // 2 flips in 90 = 2.2% (interval up to 7.7%): met, because the measured value is below 3% (D1 option b).
        var ours = oursDays(FROM, 90);
        ours = replace(ours, FROM.plusDays(10), o -> withRecovery(o, 20, false));
        ours = replace(ours, FROM.plusDays(20), o -> withRecovery(o, 20, false));
        var gr = run(ours, whoopDays(FROM, 90)).verdict().marks().get(1);
        assertThat(gr.value()).isCloseTo(2 / 90.0, within(1e-12));
        assertThat(gr.rate().high()).isGreaterThan(0.03);
        assertThat(gr.met()).isTrue();
    }

    @Test
    void oneMissedMarkMeansNotMet() {
        // 3 flips in 90 = 3.3% > 3%.
        var ours = oursDays(FROM, 90);
        for (int i : new int[] {10, 20, 30}) ours = replace(ours, FROM.plusDays(i), o -> withRecovery(o, 20, false));
        var r = run(ours, whoopDays(FROM, 90));
        assertThat(r.verdict().overall()).isEqualTo(Overall.NOT_MET);
        assertThat(r.verdict().marks().get(1).met()).isFalse();
        assertThat(r.verdict().marks().get(0).met()).isTrue();
    }

    @Test
    void aStrainMarkWithoutAnyFullCoverageDayIsNotMet() {
        var ours = oursDays(FROM, 90);
        for (int i = 0; i < 90; i++) ours = replace(ours, FROM.plusDays(i), o -> withStrain(o, o.strain(), null, 100.0));
        var r = run(ours, whoopDays(FROM, 90));
        assertThat(r.verdict().marks().get(2).value()).isNull();
        assertThat(r.verdict().marks().get(2).met()).isFalse();
        assertThat(r.verdict().overall()).isEqualTo(Overall.NOT_MET);
    }

    @Test
    void afterTheWhoopEndTheVerdictIsFinal() {
        var r = HoldoutEvaluator.evaluate(input(oursDays(FROM, 95), whoopDays(FROM, 95), WHOOP_END.plusDays(1), "ACTIVE"));
        assertThat(r.phase()).isEqualTo(Phase.ENDED);
        assertThat(r.verdict().state()).isEqualTo(State.FINAL);
        assertThat(r.verdict().overall()).isEqualTo(Overall.PASS);
    }

    @Test
    void aWindowReachingTheWhoopEndIsFinalEvenOnThatDay() {
        int n = (int) (WHOOP_END.toEpochDay() - FROM.toEpochDay()) + 1;
        var r = HoldoutEvaluator.evaluate(input(oursDays(FROM, n), whoopDays(FROM, n), WHOOP_END, "ACTIVE"));
        assertThat(r.windowTo()).isEqualTo(WHOOP_END);
        assertThat(r.verdict().state()).isEqualTo(State.FINAL);
    }

    @Test
    void endingShortOfTheMinimumIsNotEnoughDaysByTheRule() {
        var r = HoldoutEvaluator.evaluate(input(oursDays(FROM, 60), whoopDays(FROM, 60), WHOOP_END.plusDays(1), "ACTIVE"));
        assertThat(r.verdict().state()).isEqualTo(State.NOT_ENOUGH_DAYS_BY_RULE);
        assertThat(r.verdict().overall()).isNull();
        assertThat(r.verdict().recoveryDays()).isEqualTo(60);
        assertThat(r.verdict().marks().getFirst().rate().n()).isEqualTo(60);
    }

    // ---------------------------------------------------------------- H7 naive baseline

    @Test
    void naiveBaselineCopiesYesterdaysWhoopOnTheSameDaysAsOurs() {
        // Whoop: green, red, green, green from FROM-1. Ours always green (75).
        var whoop = List.of(whoop(FROM.minusDays(1), 80, 10.0), whoop(FROM, 20, 10.0), whoop(FROM.plusDays(1), 80, 10.0),
                whoop(FROM.plusDays(2), 70, 10.0));
        var r = run(oursDays(FROM, 3), whoop);
        var nv = r.naive();
        // The day before the window (a test-window day) is Whoop's own data and may be used as yesterday.
        assertThat(nv.n()).isEqualTo(3);
        assertThat(nv.skippedNoWhoopYesterday()).isZero();
        // Naive guesses: FROM green (Whoop red), FROM+1 red (Whoop green), FROM+2 green (Whoop green).
        assertThat(nv.naive().bandAgreement()).isEqualTo(Wilson.rate(1, 3, 0.95));
        assertThat(nv.naive().greenRed()).isEqualTo(Wilson.rate(2, 3, 0.95));
        assertThat(nv.naive().recoveryMae().value()).isCloseTo((60 + 60 + 10) / 3.0, within(1e-9));
        // Ours on exactly the same days: green, green, green against red, green, green.
        assertThat(nv.ours().bandAgreement()).isEqualTo(Wilson.rate(2, 3, 0.95));
        assertThat(nv.ours().greenRed()).isEqualTo(Wilson.rate(1, 3, 0.95));
        assertThat(nv.ours().recoveryMae().value()).isCloseTo((55 + 5 + 5) / 3.0, within(1e-9));
        assertThat(r.days().get(1).naiveRecovery()).isEqualTo(20);
        assertThat(r.days().get(1).naiveBand()).isEqualTo("RED");
    }

    @Test
    void naiveBaselineSkipsDaysWithoutYesterdaysWhoopRecovery() {
        var whoop = new ArrayList<>(whoopDays(FROM, 6));                   // no Whoop for FROM-1
        whoop.replaceAll(w -> w.date().equals(FROM.plusDays(2)) ? new WhoopDay(w.date(), null, 9.0, 80, 470) : w);
        var r = run(oursDays(FROM, 6), whoop);
        assertThat(r.cumulative().recovery().n()).isEqualTo(5);             // FROM+2 has no Whoop recovery
        assertThat(r.naive().n()).isEqualTo(3);                             // FROM and FROM+3 have no yesterday
        assertThat(r.naive().skippedNoWhoopYesterday()).isEqualTo(2);
        assertThat(r.naive().ours().bandAgreement().n()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- H8 retired versions and markers

    @Test
    void aRetiredVersionsComparisonHasEndedAndIsJudgedByTheRule() {
        var short_ = HoldoutEvaluator.evaluate(input(oursDays(FROM, 30), whoopDays(FROM, 30), RUNNING, "RETIRED"));
        assertThat(short_.phase()).isEqualTo(Phase.RETIRED);
        assertThat(short_.verdict().state()).isEqualTo(State.NOT_ENOUGH_DAYS_BY_RULE);
        var full = HoldoutEvaluator.evaluate(input(oursDays(FROM, 90), whoopDays(FROM, 90), RUNNING, "RETIRED"));
        assertThat(full.verdict().state()).isEqualTo(State.FINAL);
    }

    @Test
    void markersCountProfileChangesStaleFeaturesAndRecomputeRuns() {
        var profile = JSON.createObjectNode().put("model_hr_max", 184).put("now_hr_max", 186);
        var ours = oursDays(FROM, 4);
        ours = replace(ours, FROM.plusDays(2), o -> new OurDay(o.date(), o.recovery(), o.recoveryPct(), o.band(), null, false,
                o.strain(), o.strainShown(), null, o.monitoredMinutes(), o.sleepPerf(), o.sleepPerfPct(), null, o.sleepNeed(), true,
                profile, true));
        var runs = List.of(Instant.parse("2026-11-12T08:00:00Z"), Instant.parse("2026-11-10T08:00:00Z"));
        var in = input(ours, whoopDays(FROM, 4), RUNNING, "ACTIVE");
        var r = HoldoutEvaluator.evaluate(new Input(in.modelVersion(), in.status(), in.activatedAt(), in.retiredAt(), in.frozen(),
                in.marks(), 67, 33, in.ours(), in.whoop(), WHOOP_END, RUNNING, 28, runs));
        assertThat(r.markers().profileChangedDays()).isEqualTo(1);
        assertThat(r.markers().profile()).isEqualTo(profile);
        assertThat(r.markers().staleFeatureDays()).isEqualTo(1);
        assertThat(r.markers().recomputeRuns()).containsExactly(runs.get(1), runs.get(0));
    }
}
