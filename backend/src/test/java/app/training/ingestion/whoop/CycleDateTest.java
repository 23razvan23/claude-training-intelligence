package app.training.ingestion.whoop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CycleDateTest {
    static final ZoneId BUC = ZoneId.of("Europe/Bucharest");

    static Instant local(String s) {
        return java.time.LocalDateTime.parse(s).atZone(BUC).toInstant();
    }

    @Test
    void cycleStartingAtSleepOnsetBelongsToTheWakeDay() {
        // The real export: cycle starts at 23:10, wake at 06:50 the next morning.
        assertThat(WhoopCsvImporter.cycleDate(local("2026-10-20T23:10"), local("2026-10-21T06:50"), BUC))
                .isEqualTo(LocalDate.of(2026, 10, 21));
        // No wake onset in the row: start + 12 h lands on the same day.
        assertThat(WhoopCsvImporter.cycleDate(local("2026-10-20T23:10"), null, BUC)).isEqualTo(LocalDate.of(2026, 10, 21));
        // Falling asleep after midnight still belongs to that morning.
        assertThat(WhoopCsvImporter.cycleDate(local("2026-10-21T01:30"), null, BUC)).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void cycleStartingAtWakeKeepsItsDay() {
        assertThat(WhoopCsvImporter.cycleDate(local("2026-10-21T07:00"), null, BUC)).isEqualTo(LocalDate.of(2026, 10, 21));
    }

    @Test
    void implausibleWakeOnsetFallsBack() {
        // Wake onset before the start, or a day later, is ignored.
        assertThat(WhoopCsvImporter.cycleDate(local("2026-10-20T23:10"), local("2026-10-20T22:00"), BUC)).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(WhoopCsvImporter.cycleDate(local("2026-10-20T23:10"), local("2026-10-22T07:00"), BUC)).isEqualTo(LocalDate.of(2026, 10, 21));
    }
}
