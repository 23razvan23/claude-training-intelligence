package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadinessTest {
    static final LocalDate D0 = LocalDate.of(2025, 9, 15);

    static List<Readiness.Input> days(Integer[] rec, String[] band) {
        var out = new ArrayList<Readiness.Input>();
        for (int i = 0; i < rec.length; i++) out.add(new Readiness.Input(D0.plusDays(i), rec[i], band[i]));
        return out;
    }

    @Test
    void trendIsTodayMinusTheMeanOfThePreviousDays() {
        Integer[] rec = {80, 70, 60, 50, 40, 30, 20, 60};
        String[] band = {"GREEN", "GREEN", "YELLOW", "YELLOW", "YELLOW", "RED", "RED", "YELLOW"};
        var out = Readiness.compute(days(rec, band), 3, 7, D0);
        var last = out.get(7);
        assertThat(last.trendShort()).isEqualTo(60 - (40 + 30 + 20) / 3.0);
        assertThat(last.trendShortUsed()).isEqualTo(3);
        assertThat(last.trendLong()).isEqualTo(60 - (80 + 70 + 60 + 50 + 40 + 30 + 20) / 7.0);
        assertThat(last.trendLongUsed()).isEqualTo(7);
        assertThat(out.get(0).trendShort()).as("no earlier day").isNull();
    }

    @Test
    void streaksCountConsecutiveScoredDaysAndAMissingDayEndsThem() {
        Integer[] rec = {80, 55, 50, null, 45, 40, 20};
        String[] band = {"GREEN", "YELLOW", "YELLOW", null, "YELLOW", "YELLOW", "RED"};
        var out = Readiness.compute(days(rec, band), 3, 7, D0);
        assertThat(out.get(2).bandStreakDays()).isEqualTo(2);
        assertThat(out.get(5).bandStreakDays()).as("the missing day ended the earlier streak").isEqualTo(2);
        assertThat(out.get(6).bandStreakDays()).isEqualTo(1);
        assertThat(out.get(6).nonGreenStreakDays()).isEqualTo(3);
        assertThat(out.get(3).recoveryPct()).isNull();
    }

    @Test
    void nothingBeforeTheFirstHrvDay() {
        Integer[] rec = {70, 70, 70};
        String[] band = {"GREEN", "GREEN", "GREEN"};
        var out = Readiness.compute(days(rec, band), 3, 7, D0.plusDays(2));
        assertThat(out.get(0).beforeHrv()).isTrue();
        assertThat(out.get(0).recoveryPct()).isNull();
        assertThat(out.get(2).beforeHrv()).isFalse();
        assertThat(out.get(2).trendShort()).as("the scores of the days before are used as given").isEqualTo(0.0);
        assertThat(Readiness.compute(days(rec, band), 3, 7, null).get(2).beforeHrv()).as("no HRV at all").isTrue();
    }
}
