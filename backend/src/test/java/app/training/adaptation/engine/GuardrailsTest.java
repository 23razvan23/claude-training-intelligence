package app.training.adaptation.engine;

import static app.training.adaptation.engine.StartingRulesTest.of;
import static app.training.adaptation.engine.StartingRulesTest.only;
import static app.training.adaptation.engine.StartingRulesTest.texts;
import static org.assertj.core.api.Assertions.assertThat;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Session;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The SPEC.md guardrails (stage 2-3, ADR 0022), each as "given → expect". Synthetic data; today is Monday 16 Nov 2026. */
class GuardrailsTest {

    // ---- never more load on a red day ----

    @Test
    void givenARedDay_expectNoSuggestionThatAddsLoad() {
        var g = new Scenario().recovery("RED", 30);
        long easy = g.session(1, "easy", "EASY", 8.0, 48);
        var c = new Context(g.input(), g.settings);
        Session s = g.input().sessions().stream().filter(x -> x.id() == easy).findFirst().orElseThrow();
        var add = new Proposal("restore", Action.ADD, s, Change.of(s).withVolume(9.0, 54), null, null, List.of(), Confidence.MEDIUM, Map.of());
        assertThat(RulesEngine.guardrail(c, add)).isEqualTo("never more load on a red day");

        var reduce = new Proposal("volume", Action.REDUCE, s, Change.of(s).withVolume(7.0, 42), null, null, List.of(), Confidence.MEDIUM, Map.of());
        assertThat(RulesEngine.guardrail(c, reduce)).isNull();
    }

    @Test
    void givenARedDayWithEveryRuleFiring_expectOnlyChangesThatLowerOrMoveLoad() {
        var g = new Scenario().recovery("RED", 15).sleepDebt(600, 1.3);
        g.runningAcwr = 1.6;
        g.session(0, "vo2max", "KEY", 10.0, 60);
        g.session(2, "easy", "EASY", 8.0, 48);
        g.session(5, "long", "KEY", 20.0, 120);
        var r = g.run();
        assertThat(r.proposals()).isNotEmpty();
        assertThat(r.proposals()).noneMatch(RulesEngine::increasesLoad);
    }

    // ---- protect key sessions by moving them rather than deleting them ----

    @Test
    void givenARedDayAndAKeySession_expectItKeptByASwapOrMoveNeverReplacedWhileThereIsRoom() {
        var g = new Scenario().recovery("RED", 22);
        long key = g.session(0, "long_fast_finish", "KEY", 18.0, 110);
        var p = only(g.run());
        assertThat(p.session().id()).isEqualTo(key);
        assertThat(p.action()).isEqualTo(Action.MOVE);
        assertThat(p.after().type()).isEqualTo("long_fast_finish");
        assertThat(p.after().distanceKm()).isEqualTo(18.0);
    }

    @Test
    void givenARedDayAndNoRoomAtAllForAKeySession_expectAnEasyRunWithMediumConfidenceAndTheReasonSaid() {
        var g = new Scenario().recovery("RED", 22);
        g.today = LocalDate.of(2026, 11, 22); // Sunday: the week ends today
        long key = g.session(0, "threshold", "KEY", 10.0, 60);
        g.session(1, "vo2max", "KEY", 8.0, 50);
        g.session(2, "hills", "SUPPORTING", 8.0, 50);
        g.session(3, "long", "KEY", 22.0, 130);
        var p = of(g.run(), key);
        assertThat(p.action()).isEqualTo(Action.REPLACE);
        assertThat(p.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(texts(p).get(1)).contains("No easy or free day in the next 3 days to move this key session to");
    }

    // ---- hold weekly targets unless recovery forces otherwise ----

    @Test
    void givenARedSaturday_expectTheSwapToStayInTheWeekSoItsKmAreUnchanged() {
        var g = new Scenario().recovery("RED", 28);
        g.today = LocalDate.of(2026, 11, 21); // Saturday
        long hills = g.session(0, "hills", "SUPPORTING", 10.0, 60);
        long sunday = g.session(1, "easy", "EASY", 8.0, 48);
        g.session(2, "easy", "EASY", 8.0, 48); // Monday, next week, also within 3 days
        var p = only(g.run());
        assertThat(p.action()).isEqualTo(Action.SWAP);
        assertThat(p.session().id()).isEqualTo(hills);
        assertThat(p.counterpart().id()).isEqualTo(sunday);
        assertThat(Model.sameWeek(p.after().date(), g.today)).isTrue();
        assertThat(p.after().distanceKm() + p.counterpartAfter().distanceKm())
                .isEqualTo(p.session().distanceKm() + p.counterpart().distanceKm());
    }

    @Test
    void givenARedSundayWithTheOnlyEasyDayNextWeek_expectTheSwapAndTheReasonSaysTheWeekHadNoRoom() {
        var g = new Scenario().recovery("RED", 28);
        g.today = LocalDate.of(2026, 11, 22); // Sunday
        g.session(0, "hills", "SUPPORTING", 10.0, 60);
        g.session(1, "easy", "EASY", 8.0, 48);
        var p = only(g.run());
        assertThat(p.action()).isEqualTo(Action.SWAP);
        assertThat(texts(p).get(1)).endsWith("there was no easy day left this week.");
    }

    @Test
    void givenAGreenDayAndNoOtherSignal_expectThePlanLeftAlone() {
        var g = new Scenario().recovery("GREEN", 85);
        g.session(0, "threshold", "KEY", 10.0, 60);
        g.session(1, "easy", "EASY", 8.0, 48);
        g.session(5, "long", "KEY", 20.0, 120);
        assertThat(g.run().proposals()).isEmpty();
    }

    // ---- when signals conflict, choose the conservative option and say so ----

    @Test
    void givenGreenRecoveryButIllness_expectTheCheckInToWinAndTheReasonToSaySo() {
        var g = new Scenario().recovery("GREEN", 84).illness(0);
        g.session(0, "easy", "EASY", 8.0, 48);
        var p = only(g.run());
        assertThat(p.after().type()).isEqualTo("rest");
        assertThat(texts(p)).last().asString().isEqualTo(
                "Recovery is green (84%), but the check-in says otherwise; the check-in comes first, the conservative option.");
    }

    @Test
    void givenYellowAndSleepDebtOnAVo2maxDay_expectTheMoreConservativeEasyRunAndBothSignalsNamed() {
        var g = new Scenario().recovery("YELLOW", 50).streak(2).sleepDebt(520, 1.15);
        g.session(0, "vo2max", "KEY", 10.0, 60);
        var p = only(g.run());
        assertThat(p.rule()).isEqualTo("sleep");
        assertThat(p.after().type()).isEqualTo("easy");
        assertThat(p.reasons()).hasSize(3);
        assertThat(texts(p).get(2)).startsWith("Other signals point elsewhere (Recovery has not been green for 2 days")
                .endsWith("the more conservative option was chosen.");
    }

    @Test
    void givenARedDayAndANiggle_expectNoIntensityMovedToALaterDay() {
        var g = new Scenario().recovery("RED", 25).niggle("achilles", 1);
        long threshold = g.session(0, "threshold", "KEY", 10.0, 60);
        g.session(2, "easy", "EASY", 8.0, 48);
        var p = of(g.run(), threshold);
        assertThat(p.rule()).isEqualTo("niggle");
        assertThat(p.action()).isEqualTo(Action.REPLACE);
        assertThat(p.after().type()).isEqualTo("easy");
    }

    @Test
    void givenSeveralRules_expectAtMostOneSuggestionPerSessionAndAtMostThreeReasons() {
        var g = new Scenario().recovery("RED", 15).sleepDebt(700, 1.5).niggle("knee", 4).illness(1);
        g.runningAcwr = 1.6;
        g.tolerance = 20.0;
        g.session(0, "vo2max", "KEY", 10.0, 60);
        g.session(1, "easy", "EASY", 12.0, 70);
        g.session(3, "threshold", "KEY", 10.0, 60);
        g.session(5, "long", "KEY", 24.0, 140);
        var r = g.run();
        var ids = r.proposals().stream().flatMap(p -> p.counterpart() == null ? java.util.stream.Stream.of(p.session().id())
                : java.util.stream.Stream.of(p.session().id(), p.counterpart().id())).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(r.proposals()).allMatch(p -> !p.reasons().isEmpty() && p.reasons().size() <= 3);
    }

    // ---- race week and taper get only reductions ----

    @Test
    void givenRaceWeekAndARedDay_expectAnEasyRunInsteadOfASwap() {
        var g = new Scenario().recovery("RED", 25);
        long threshold = g.session(0, "threshold", "KEY", 8.0, 50);
        g.session(2, "easy", "EASY", 6.0, 36);
        g.session(6, "race", "KEY", 21.1, 100); // Sunday's race: Monday is in race week
        var p = of(g.run(), threshold);
        assertThat(p.action()).isEqualTo(Action.REPLACE);
        assertThat(p.after().type()).isEqualTo("easy");
        assertThat(texts(p).get(1)).isEqualTo("It is race week or taper, so only a reduction is suggested, not a move.");
    }

    @Test
    void givenTaperSessions_expectNoVolumeRestoredOnThem() {
        var g = new Scenario().recovery("GREEN", 82);
        g.tolerance = 45.0;
        for (int week = 1; week <= 2; week++) {
            g.add(-7 * week + 1, "easy", "EASY", 20.0, 120, "DONE", false);
            g.ran(7 * week - 1, 10);
        }
        g.add(1, "easy", "EASY", 8.0, 48, "PLANNED", true);
        g.add(3, "easy", "EASY", 8.0, 48, "PLANNED", true);
        assertThat(g.run().proposals()).isEmpty();
    }

    @Test
    void givenAMoveIntoRaceWeek_expectTheGuardrailToDropIt() {
        var g = new Scenario();
        long key = g.session(0, "threshold", "KEY", 10.0, 60);
        g.session(6, "race", "KEY", 10.0, 45);
        var c = new Context(g.input(), g.settings);
        Session s = g.input().sessions().stream().filter(x -> x.id() == key).findFirst().orElseThrow();
        var move = new Proposal("red", Action.MOVE, s, Change.of(s).withDate(g.today.plusDays(3)), null, null, List.of(), Confidence.HIGH, Map.of());
        assertThat(RulesEngine.guardrail(c, move)).isEqualTo("race week and taper get only reductions");
    }

    @Test
    void givenARaceToday_expectTheEngineToLeaveTheRaceAlone() {
        var g = new Scenario().recovery("RED", 10).illness(0);
        g.session(0, "race", "KEY", 42.2, 200);
        assertThat(g.run().proposals()).isEmpty();
    }

    // ---- ACWR is one signal among several, never a gate on its own ----

    @Test
    void givenOnlyAHighAcwr_expectNeverMoreThanALowConfidenceTrimOfEasyRuns() {
        var g = new Scenario();
        g.runningAcwr = 2.0;
        g.session(0, "threshold", "KEY", 10.0, 60);
        g.session(2, "long", "KEY", 20.0, 120);
        g.session(4, "easy", "EASY", 10.0, 60);
        var r = g.run();
        assertThat(r.proposals()).singleElement().satisfies(p -> {
            assertThat(p.session().type()).isEqualTo("easy");
            assertThat(p.confidence()).isEqualTo(Confidence.LOW);
        });
    }

    // ---- determinism and what the engine leaves alone ----

    @Test
    void givenTheSameInputTwice_expectTheSameSuggestions() {
        var g = new Scenario().recovery("YELLOW", 50).streak(2).niggle("hip", 3);
        g.tolerance = 25.0;
        g.session(0, "threshold", "KEY", 10.0, 60);
        g.session(2, "easy", "EASY", 12.0, 70);
        g.session(5, "long", "KEY", 20.0, 120);
        assertThat(g.run()).isEqualTo(g.run());
    }

    @Test
    void givenASessionAlreadyDecidedToday_expectItLeftAloneUntilTomorrow() {
        var g = new Scenario().recovery("YELLOW", 50).streak(2);
        long t = g.session(0, "threshold", "KEY", 10.0, 60);
        g.skip.add(t);
        assertThat(g.run().proposals()).isEmpty();
    }

    @Test
    void givenNoSignalsForToday_expectANoteAndOnlyTheVolumeRule() {
        var g = new Scenario();
        g.beforeHrv = true;
        g.session(0, "threshold", "KEY", 10.0, 60);
        var r = g.run();
        assertThat(r.proposals()).isEmpty();
        assertThat(r.notes()).anyMatch(n -> n.contains("no readiness"));
    }
}
