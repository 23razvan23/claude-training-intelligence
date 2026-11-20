package app.training.adaptation.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.adaptation.backtest.ActivityPlan.PlanActivity;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How an activity becomes a session of the stand-in plan (ADR 0023 Q1). */
class ActivityPlanTest {
    static final LocalDate D = LocalDate.of(2026, 3, 4);
    static final BacktestParams.Classification C = BacktestParams.Classification.DEFAULTS;

    static PlanActivity run(int minutes, double km, Double z4, Double z5) {
        return new PlanActivity(1, D, D, "running", "running", minutes * 60, km * 1000, z4, z5);
    }

    static String type(PlanActivity a) {
        return ActivityPlan.classify(a, C, Set.of());
    }

    @Test
    void runsByHeartRateZoneAndLength() {
        assertThat(type(run(45, 9, 2.0, 0.0))).isEqualTo("easy");
        assertThat(type(run(50, 10, 12.0, 9.0))).as("8+ min above 90 %").isEqualTo("vo2max");
        assertThat(type(run(50, 10, 10.0, 7.0))).as("17 min above 80 %, under 8 above 90 %").isEqualTo("threshold");
        assertThat(type(run(50, 10, 8.0, 6.0))).as("14 min above 80 %").isEqualTo("easy");
        assertThat(type(run(95, 16, 3.0, 0.0))).as("90+ min").isEqualTo("long");
        assertThat(type(run(80, 18, 3.0, 0.0))).as("18+ km").isEqualTo("long");
        assertThat(type(run(100, 20, 8.0, 3.0))).as("long with 10+ min above 80 %").isEqualTo("long_fast_finish");
        assertThat(type(run(100, 20, 30.0, 20.0))).as("long beats VO2max").isEqualTo("long_fast_finish");
        assertThat(type(run(40, 8, null, null))).as("no heart rate: easy").isEqualTo("easy");
        assertThat(type(run(100, 20, null, null))).isEqualTo("long");
    }

    @Test
    void racesAreTheDatesTheCoachNames() {
        assertThat(ActivityPlan.classify(run(40, 10, 5.0, 30.0), C, Set.of(D))).isEqualTo("race");
    }

    @Test
    void crossTrainingAndWhatIsLeftOut() {
        assertThat(type(new PlanActivity(2, D, D, "cycling", "mountain_biking", 3600, 20000.0, null, null))).isEqualTo("cross");
        assertThat(type(new PlanActivity(2, D, D, "strength", "strength_training", 2400, null, null, null))).isEqualTo("cross");
        assertThat(type(new PlanActivity(2, D, D, "other", "HIIT", 1800, null, null, null))).isEqualTo("cross");
        assertThat(type(new PlanActivity(2, D, D, "other", "squash", 3600, null, null, null))).isEqualTo("cross");
        assertThat(type(new PlanActivity(2, D, D, "walking", "walking", 3600, 5000.0, null, null))).isNull();
        assertThat(type(new PlanActivity(2, D, D, "other", "breathwork", 600, null, null, null))).isNull();
        assertThat(type(new PlanActivity(2, D, D, "mobility", "yoga", 1800, null, null, null))).isNull();
    }

    @Test
    void sessionsBeforeTheMorningAreDoneTheRestPlannedWithTheSettingsDemand() {
        var p = BacktestParams.lastYear(D);
        var acts = List.of(new PlanActivity(10, D.minusDays(1), D.minusDays(1), "running", "running", 3000, 10000.0, 20.0, 1.0),
                new PlanActivity(11, D, D, "running", "running", 2700, 8040.0, 0.0, 0.0),
                new PlanActivity(12, D.plusDays(30), D.plusDays(30), "running", "running", 2700, 8000.0, 0.0, 0.0));
        var s = ActivityPlan.sessions(acts, D, D.minusDays(28), D.plusDays(14), p, t -> "threshold".equals(t) ? "KEY" : "EASY");
        assertThat(s).hasSize(2);
        assertThat(s.get(0).status()).isEqualTo("DONE");
        assertThat(s.get(0).demand()).isEqualTo("KEY");
        assertThat(s.get(1).status()).isEqualTo("PLANNED");
        assertThat(s.get(1).distanceKm()).isEqualTo(8.0);
        assertThat(s.get(1).durationMin()).isEqualTo(45);
        assertThat(s.get(1).planId()).isNull();
    }
}
