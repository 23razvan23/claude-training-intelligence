package app.training.adaptation.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.adaptation.signals.Subjective;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** What existed on a past morning (ADR 0023 D2). */
class AsOfTest {
    static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
    static final LocalDate D = LocalDate.of(2026, 3, 4);
    static final BacktestParams P = new BacktestParams(D, D, LocalTime.of(9, 0), List.of(), List.of(), null, null);
    static final Instant CUTOFF = AsOf.cutoff(D, P, ZONE);

    static AsOf.CheckinRow row(long id, LocalDate day, String source, Instant created, LocalDate activityDay, boolean ill) {
        return new AsOf.CheckinRow(id, day, activityDay == null ? "day" : "activity:" + id, created, source, activityDay,
                new Subjective.Checkin(day, activityDay == null, null, null, null, null, false, false, ill));
    }

    @Test
    void theCutoffIsNineInTheAthletesTimezone() {
        assertThat(CUTOFF).isEqualTo(Instant.parse("2026-03-04T07:00:00Z")); // 09:00 EET = 07:00 UTC
    }

    @Test
    void manualRowsCountByTypingTimeSeedRowsByTheirActivity() {
        var rows = List.of(row(1, D, "MANUAL", CUTOFF, null, true), // exactly at the cut-off: counts
                row(2, D.minusDays(5), "MANUAL", CUTOFF.plusSeconds(1), null, true), // back-dated, typed later: no
                row(3, D.minusDays(1), "GARMIN_SEED", Instant.parse("2026-11-18T10:00:00Z"), D.minusDays(1), false), // imported later, run before: yes
                row(4, D, "GARMIN_SEED", Instant.parse("2026-11-18T10:00:00Z"), D, false)); // today's run: no
        var got = AsOf.checkins(rows, D, CUTOFF);
        assertThat(got).extracting(Subjective.Checkin::date).containsExactly(D.minusDays(1), D);
    }

    @Test
    void lateGarminCorrectionsAreSaid() {
        var data = new AsOf.Data(ZONE, D.minusDays(100), Map.of(), Map.of(), Map.of(), Map.of(), new TreeSet<>(), List.of(), List.of(), List.of(),
                List.of(new AsOf.Revision(D, CUTOFF.plusSeconds(3600)), new AsOf.Revision(D.minusDays(1), CUTOFF.minusSeconds(60)),
                        new AsOf.Revision(D.minusDays(5), CUTOFF.plusSeconds(3600))));
        assertThat(AsOf.lateRevisions(data, D, P)).singleElement().asString().contains(D.toString());
    }
}
