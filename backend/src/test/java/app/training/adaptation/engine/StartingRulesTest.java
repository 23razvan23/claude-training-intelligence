package app.training.adaptation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Reason;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One scenario per SPEC.md starting rule, written "given (signals) → expect (suggestion + reason)" (stage 2-3,
 * ADR 0022). Today is Monday 16 November 2026 in every scenario; all data is synthetic.
 */
class StartingRulesTest {

    static Proposal only(Model.Result r) {
        assertThat(r.proposals()).hasSize(1);
        return r.proposals().get(0);
    }

    static Proposal of(Model.Result r, long sessionId) {
        return r.proposals().stream().filter(p -> p.session().id() == sessionId).findFirst().orElseThrow(
                () -> new AssertionError("no suggestion for session " + sessionId + " in " + r.proposals()));
    }

    static List<String> texts(Proposal p) {
        return p.reasons().stream().map(Reason::text).toList();
    }

    @Nested
    class Rule1RedRecoveryAndHardSession {
        @Test
        void givenRedAndAThresholdToday_expectSwapWithTheNextEasyDayThisWeek() {
            var g = new Scenario().recovery("RED", 28);
            long threshold = g.session(0, "threshold", "KEY", 10.0, 60);
            long easy = g.session(2, "easy", "EASY", 8.0, 45);

            var p = only(g.run());
            assertThat(p.action()).isEqualTo(Action.SWAP);
            assertThat(p.session().id()).isEqualTo(threshold);
            assertThat(p.counterpart().id()).isEqualTo(easy);
            assertThat(p.after().date()).isEqualTo(Scenario.MONDAY.plusDays(2));
            assertThat(p.counterpartAfter().date()).isEqualTo(Scenario.MONDAY);
            assertThat(texts(p)).containsExactly("Recovery is red today (28%).",
                    "Threshold session swaps with Wednesday 18 Nov's easy run, so it stays in this week.");
            assertThat(p.confidence()).isEqualTo(Confidence.HIGH);
        }

        @Test
        void givenRedAndAKeySessionWithNoEasyDay_expectMoveToAFreeDayThisWeek() {
            var g = new Scenario().recovery("RED", 25);
            long key = g.session(0, "threshold", "KEY", 10.0, 60);
            g.session(1, "hills", "SUPPORTING", 8.0, 50);
            g.session(3, "long", "KEY", 20.0, 120);

            var p = of(g.run(), key);
            assertThat(p.action()).isEqualTo(Action.MOVE);
            // Tuesday has hills, Wednesday and Friday sit next to the Thursday long run; Saturday is free.
            assertThat(p.after().date()).isEqualTo(Scenario.MONDAY.plusDays(5));
            assertThat(texts(p).get(1)).contains("moves to Saturday 21 Nov, a free day this week");
        }

        @Test
        void givenRedAndASupportingSessionWithNoEasyDay_expectAnEasyRunInstead() {
            var g = new Scenario().recovery("RED", 20);
            long hills = g.session(0, "hills", "SUPPORTING", 10.0, 60);

            var p = only(g.run());
            assertThat(p.action()).isEqualTo(Action.REPLACE);
            assertThat(p.after().type()).isEqualTo("easy");
            assertThat(p.after().distanceKm()).isEqualTo(6.0);
            assertThat(p.after().durationMin()).isEqualTo(36);
            assertThat(p.session().id()).isEqualTo(hills);
            assertThat(texts(p).get(1)).isEqualTo("No easy day in the next 3 days to swap with, so it becomes an easy run.");
        }

        @Test
        void givenGreen_expectNothing() {
            var g = new Scenario().recovery("GREEN", 80);
            g.session(0, "threshold", "KEY", 10.0, 60);
            g.session(2, "easy", "EASY", 8.0, 45);
            assertThat(g.run().proposals()).isEmpty();
        }
    }

    @Nested
    class Rule2YellowTwoDaysAndHardSession {
        @Test
        void givenYellowForTwoDaysAndAThreshold_expectReduceBy25PercentKeepingWarmUp() {
            var g = new Scenario().recovery("YELLOW", 52).streak(2);
            g.session(0, "threshold", "KEY", 10.0, 60);

            var p = only(g.run());
            assertThat(p.action()).isEqualTo(Action.REDUCE);
            assertThat(p.after().distanceKm()).isEqualTo(7.5);
            assertThat(p.after().durationMin()).isEqualTo(45);
            assertThat(p.after().description()).startsWith("Engine: cut reps or time at intensity by 25%; keep the warm-up");
            assertThat(texts(p)).containsExactly("Recovery has not been green for 2 days in a row (yellow today, 52%).",
                    "Reduce the work at intensity by 25%, keeping the warm-up and the purpose.");
            assertThat(p.confidence()).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        void givenYellowOnlyToday_expectNothing() {
            var g = new Scenario().recovery("YELLOW", 55).streak(1);
            g.session(0, "threshold", "KEY", 10.0, 60);
            assertThat(g.run().proposals()).isEmpty();
        }

        @Test
        void givenYellowForTwoDaysAndOnlyAnEasyRun_expectNothing() {
            var g = new Scenario().recovery("YELLOW", 55).streak(3);
            g.session(0, "easy", "EASY", 8.0, 45);
            assertThat(g.run().proposals()).isEmpty();
        }
    }

    @Nested
    class Rule3RunningVolumeOverToleranceOrAcwr {
        @Test
        void givenAWeekPlannedAboveTolerance_expectEasyRunsTrimmedFirstThenTheLongRun() {
            var g = new Scenario();
            g.tolerance = 37.6;                       // limit = 37.6 × 1.1 = 41.36 km
            g.ran(6, 8).ran(4, 6).ran(2, 6);           // last Tue, Thu, Sat
            long mon = g.session(0, "easy", "EASY", 10.0, 60);
            long tue = g.session(1, "easy", "EASY", 10.0, 60);
            long thu = g.session(3, "threshold", "KEY", 10.0, 55);
            long sat = g.session(5, "long", "KEY", 20.0, 120);

            var r = g.run();
            // The 7 days to Saturday hold 50 km: 8.64 km too many. Easy runs give at most 30% each (6 km), the long run the rest.
            assertThat(of(r, mon).after().distanceKm()).isEqualTo(7.0);
            assertThat(of(r, tue).after().distanceKm()).isEqualTo(7.0);
            assertThat(of(r, tue).after().durationMin()).isEqualTo(42);
            assertThat(of(r, sat).after().distanceKm()).isEqualTo(17.4);
            assertThat(r.proposals()).noneMatch(p -> p.session().id() == thu);
            assertThat(r.proposals()).allMatch(p -> p.action() == Action.REDUCE && p.confidence() == Confidence.MEDIUM);
            assertThat(texts(of(r, mon))).containsExactly(
                    "Running in the 7 days to Saturday 21 Nov would be 50 km, above the tolerance of 37.6 km/week + 10% (41.4 km).",
                    "Easy runs are trimmed first: this one by 3 km; no volume is added.");
            assertThat(texts(of(r, sat)).get(1)).isEqualTo("Easy runs alone could not bring it down, so the long run is shortened by 2.6 km; no volume is added.");
        }

        @Test
        void givenOnlyTheRunningAcwrHigh_expectALightTrimWithLowConfidence() {
            var g = new Scenario();
            g.runningAcwr = 1.45;
            long easy = g.session(1, "easy", "EASY", 10.0, 60);
            g.session(3, "threshold", "KEY", 10.0, 55);

            var p = only(g.run());
            assertThat(p.session().id()).isEqualTo(easy);
            assertThat(p.after().distanceKm()).isEqualTo(8.0);
            assertThat(p.confidence()).isEqualTo(Confidence.LOW);
            assertThat(texts(p).get(0)).isEqualTo("Running ACWR is 1.45, above 1.30: recent running load rose fast compared with the last 4 weeks.");
            assertThat(texts(p).get(1)).contains("one signal among several", "disputed");
        }

        @Test
        void givenVolumeAndAcwrBothHigh_expectHighConfidence() {
            var g = new Scenario();
            g.tolerance = 30.0;
            g.runningAcwr = 1.5;
            g.session(0, "easy", "EASY", 12.0, 70);
            g.session(2, "easy", "EASY", 12.0, 70);
            g.session(4, "long", "KEY", 18.0, 110);

            var r = g.run();
            assertThat(r.proposals()).isNotEmpty().allMatch(p -> p.confidence() == Confidence.HIGH);
            assertThat(texts(r.proposals().get(0)).get(2)).contains("Running ACWR is 1.50", "one signal among several");
        }

        @Test
        void givenARunThisMorningNotYetMarkedDone_expectTodayCountedOnce() {
            var g = new Scenario();
            g.tolerance = 30.0;                       // limit 33 km
            g.ran(0, 10);                              // this morning's run, session still PLANNED
            g.session(0, "easy", "EASY", 10.0, 60);
            g.session(3, "long", "KEY", 22.0, 130);
            assertThat(g.run().proposals()).isEmpty(); // 32 km, not 42
        }

        @Test
        void givenAWeekWithinTolerance_expectNothing() {
            var g = new Scenario();
            g.tolerance = 50.0;
            g.session(0, "easy", "EASY", 10.0, 60);
            g.session(5, "long", "KEY", 20.0, 120);
            assertThat(g.run().proposals()).isEmpty();
        }
    }

    @Nested
    class Rule4SleepDebt {
        @Test
        void givenSleepDebtOverOneNightAndVo2maxToday_expectThresholdInstead() {
            var g = new Scenario().recovery("GREEN", 75).sleepDebt(560, 1.2);
            long vo2 = g.session(0, "vo2max", "KEY", 10.0, 60);

            var p = only(g.run());
            assertThat(p.session().id()).isEqualTo(vo2);
            assertThat(p.action()).isEqualTo(Action.REPLACE);
            assertThat(p.after().type()).isEqualTo("threshold");
            assertThat(p.after().durationMin()).isEqualTo(60);
            assertThat(texts(p)).containsExactly("Sleep debt is 9 h 20 min over the last nights, 1.2 nights of sleep need (limit 1).",
                    "No VO2max work today; threshold work instead, same time.");
            assertThat(p.confidence()).isEqualTo(Confidence.HIGH);
        }

        @Test
        void givenSleepDebtAndYellow_expectEasyInstead() {
            var g = new Scenario().recovery("YELLOW", 58).streak(1).sleepDebt(500, 1.1);
            g.session(0, "vo2max", "KEY", 10.0, 60);
            var p = only(g.run());
            assertThat(p.after().type()).isEqualTo("easy");
            assertThat(texts(p).get(1)).contains("an easy run is the safer choice");
        }

        @Test
        void givenSleepDebtUnderOneNight_expectNothing() {
            var g = new Scenario().sleepDebt(300, 0.7);
            g.session(0, "vo2max", "KEY", 10.0, 60);
            assertThat(g.run().proposals()).isEmpty();
        }
    }

    @Nested
    class Rule5Niggle {
        @Test
        void givenAKneeNiggle_expectIntensityRemovedAndAHealthMessageAfter3Days() {
            var g = new Scenario().niggle("knee", 2);
            long easy = g.session(0, "easy", "EASY", 8.0, 45);
            long threshold = g.session(2, "threshold", "KEY", 10.0, 60);
            long hills = g.session(4, "hills", "SUPPORTING", 9.0, 55);

            var r = g.run();
            assertThat(r.proposals()).extracting(p -> p.session().id()).containsExactly(threshold, hills);
            var p = of(r, threshold);
            assertThat(p.action()).isEqualTo(Action.REPLACE);
            assertThat(p.after().type()).isEqualTo("easy");
            assertThat(p.after().durationMin()).isEqualTo(60);
            assertThat(p.after().description()).contains("flat ground", "no downhill", "Cross-training");
            assertThat(texts(p).get(0)).isEqualTo("A niggle (knee) is open since Saturday 14 Nov, 3 days.");
            assertThat(texts(p).get(1)).contains("cross-training");
            assertThat(r.proposals()).noneMatch(x -> x.session().id() == easy);
            assertThat(r.healthMessages()).singleElement().extracting(Reason::text).asString()
                    .contains("knee niggle has lasted 3 days", "physio or doctor");
        }

        @Test
        void givenANiggleOfOneDay_expectNoHealthMessageYet() {
            var g = new Scenario().niggle("calf", 0);
            g.session(1, "vo2max", "KEY", 10.0, 60);
            var r = g.run();
            assertThat(r.proposals()).hasSize(1);
            assertThat(r.healthMessages()).isEmpty();
        }
    }

    @Nested
    class Rule6Illness {
        @Test
        void givenIllnessToday_expectRestToday() {
            var g = new Scenario().illness(0);
            long easy = g.session(0, "easy", "EASY", 8.0, 45);
            var p = only(g.run());
            assertThat(p.session().id()).isEqualTo(easy);
            assertThat(p.action()).isEqualTo(Action.REPLACE);
            assertThat(p.after().type()).isEqualTo("rest");
            assertThat(texts(p).get(0)).isEqualTo("Illness was reported today: rest or very easy until symptoms clear.");
        }

        @Test
        void givenIllnessAndAKeySession_expectItMovedPastTheReturnDays() {
            var g = new Scenario().illness(1); // yesterday: still current today
            long key = g.session(0, "threshold", "KEY", 10.0, 60);
            var p = only(g.run());
            assertThat(p.session().id()).isEqualTo(key);
            assertThat(p.action()).isEqualTo(Action.MOVE);
            // Ill Sunday and Monday; return days Tuesday and Wednesday; normal from Thursday.
            assertThat(p.after().date()).isEqualTo(Scenario.MONDAY.plusDays(3));
        }

        @Test
        void givenTheFirstDayBack_expectEasyInsteadOfHard() {
            var g = new Scenario().illness(2);
            g.session(0, "hills", "SUPPORTING", 10.0, 60);
            var p = only(g.run());
            assertThat(p.after().type()).isEqualTo("easy");
            assertThat(texts(p).get(0)).startsWith("Day 1 of a 2-day graded return after illness");
        }

        @Test
        void givenTheSecondDayBack_expectAReduction() {
            var g = new Scenario().illness(3);
            g.session(0, "hills", "SUPPORTING", 10.0, 60);
            var p = only(g.run());
            assertThat(p.action()).isEqualTo(Action.REDUCE);
            assertThat(p.after().distanceKm()).isEqualTo(7.5);
            assertThat(texts(p).get(0)).startsWith("Day 2 of a 2-day graded return");
        }

        @Test
        void givenIllnessLongAgo_expectNothing() {
            var g = new Scenario().illness(4);
            g.session(0, "threshold", "KEY", 10.0, 60);
            assertThat(g.run().proposals()).isEmpty();
        }
    }

    @Nested
    class Rule7GreenAndWellUnderPlan {
        private Scenario underPlan() {
            var g = new Scenario().recovery("GREEN", 82);
            g.tolerance = 45.0;
            for (int week = 1; week <= 2; week++) {
                int mon = -7 * week;
                g.add(mon + 1, "easy", "EASY", 10.0, 60, "DONE", false);
                g.add(mon + 3, "threshold", "KEY", 10.0, 60, "SKIPPED", false);
                g.add(mon + 5, "long", "KEY", 20.0, 120, "DONE", false);
                g.ran(-(mon + 1), 10).ran(-(mon + 5), 15); // 25 of 40 km each week
            }
            return g;
        }

        @Test
        void givenGreenAndTwoWeeksUnderPlan_expectOneProgressionStepOnTheEasyRuns() {
            var g = underPlan();
            long e1 = g.session(1, "easy", "EASY", 8.0, 48);
            long e2 = g.session(3, "easy", "EASY", 8.0, 48);
            g.session(5, "long", "KEY", 16.0, 100);

            var r = g.run();
            // Coming week: 32 km planned; one step = 10% = 3.2 km, spread over the two easy runs.
            assertThat(r.proposals()).extracting(p -> p.session().id()).containsExactly(e1, e2);
            var p = of(r, e1);
            assertThat(p.action()).isEqualTo(Action.ADD);
            assertThat(p.after().distanceKm()).isEqualTo(9.6);
            assertThat(p.after().durationMin()).isEqualTo(58);
            assertThat(texts(p).get(0)).isEqualTo("Recovery is green today (82%) and running was well under plan: 25 of 40 km (week of "
                    + "Monday 9 Nov); 25 of 40 km (week of Monday 2 Nov).");
            assertThat(texts(p).get(1)).startsWith("One progression step: 3.2 km more over the next 7 days (10% of the planned km)");
        }

        @Test
        void givenAStepAcceptedThisWeek_expectNoSecondStep() {
            var g = underPlan();
            g.restoreRecently = true;
            g.session(1, "easy", "EASY", 8.0, 48);
            assertThat(g.run().proposals()).isEmpty();
        }

        @Test
        void givenOnlyOneWeekUnderPlan_expectNothing() {
            var g = underPlan();
            g.ran(9, 15); // the week of 2 Nov is now on plan: 40 of 40 km
            g.session(1, "easy", "EASY", 8.0, 48);
            assertThat(g.run().proposals()).isEmpty();
        }

        @Test
        void givenTheStepWouldPassTolerance_expectItCappedAtTolerance() {
            var g = underPlan();
            g.tolerance = 33.0;
            g.session(1, "easy", "EASY", 8.0, 48);
            g.session(3, "easy", "EASY", 8.0, 48);
            g.session(5, "long", "KEY", 16.0, 100);
            var r = g.run();
            // The fullest 7 days hold 32 km; only 1 km of room is left below 33 km/week.
            assertThat(r.proposals()).extracting(p -> p.after().distanceKm()).containsExactly(8.5, 8.5);
        }
    }
}
