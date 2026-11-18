package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubjectiveTest {
    static final LocalDate D0 = LocalDate.of(2026, 11, 1);

    static List<LocalDate> grid(int n) {
        var out = new ArrayList<LocalDate>();
        for (int i = 0; i < n; i++) out.add(D0.plusDays(i));
        return out;
    }

    static Subjective.Checkin day(int i, Integer rpe, Integer soreness) {
        return new Subjective.Checkin(D0.plusDays(i), true, rpe, soreness, null, null, false, false, false);
    }

    static Subjective.Checkin niggle(int i, String where, Integer severity, boolean gone) {
        return new Subjective.Checkin(D0.plusDays(i), true, null, null, where, severity, gone, false, false);
    }

    @Test
    void rpeSorenessIllnessAndInjuryOfTheDay() {
        var rows = List.of(day(0, 6, 2), new Subjective.Checkin(D0, false, 8, 4, null, null, false, false, true),
                new Subjective.Checkin(D0.plusDays(2), true, null, null, null, null, false, true, false));
        var out = Subjective.compute(grid(3), rows, 7);
        assertThat(out.get(0).dayRpe()).isEqualTo(6);
        assertThat(out.get(0).maxSessionRpe()).isEqualTo(8);
        assertThat(out.get(0).soreness()).isEqualTo(4);
        assertThat(out.get(0).illness()).isTrue();
        assertThat(out.get(0).checkins()).isEqualTo(2);
        assertThat(out.get(1).checkins()).isZero();
        assertThat(out.get(1).illnessDaysLast7()).isEqualTo(1);
        assertThat(out.get(2).injury()).isTrue();
        assertThat(out.get(2).flagged()).isTrue();
        assertThat(out.get(0).flagged()).as("illness alone does not flag tolerance").isFalse();
    }

    @Test
    void aNiggleLastsUntilItIsMarkedGoneAndCountsItsDays() {
        var rows = List.of(niggle(0, "calf", 2, false), niggle(2, "calf", 3, false), niggle(4, "calf", null, true));
        var out = Subjective.compute(grid(6), rows, 7);
        assertThat(out.get(0).niggles()).singleElement().satisfies(n -> {
            assertThat(n.location()).isEqualTo("calf");
            assertThat(n.persistDays()).isEqualTo(1);
        });
        assertThat(out.get(3).niggles()).singleElement().satisfies(n -> {
            assertThat(n.persistDays()).isEqualTo(4);
            assertThat(n.severity()).isEqualTo(3);
        });
        assertThat(out.get(4).niggles()).isEmpty();
        assertThat(out.get(1).flagged()).as("only days with a report are flagged").isFalse();
        assertThat(out.get(4).flagged()).as("a 'gone' report is not a niggle").isFalse();
    }

    @Test
    void aNiggleNotReportedForStaleDaysEndsAndALaterReportStartsAgain() {
        var rows = List.of(niggle(0, "knee", 2, false), niggle(10, "knee", 1, false));
        var out = Subjective.compute(grid(12), rows, 7);
        assertThat(out.get(6).niggles()).hasSize(1);
        assertThat(out.get(7).niggles()).as("7 days without a report").isEmpty();
        assertThat(out.get(11).niggles()).singleElement().satisfies(n -> assertThat(n.since()).isEqualTo(D0.plusDays(10)));
    }

    @Test
    void aNiggleReportedBeforeTheGridIsStillOpen() {
        var rows = List.of(niggle(-2, "hip", 2, false));
        var out = Subjective.compute(grid(2), rows, 7);
        assertThat(out.get(1).niggles()).singleElement().satisfies(n -> assertThat(n.persistDays()).isEqualTo(4));
    }
}
