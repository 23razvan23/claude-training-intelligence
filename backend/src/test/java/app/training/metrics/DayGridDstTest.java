package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * R-12: the scoring day grid (ADR 0008 S1) is plain calendar dates stepped by one day, with no
 * clock time or zone in it, so the days the clocks change are 24-hour days like any other: no
 * missing date, no repeated date. Zone handling happens earlier, when cycle dates are assigned
 * ({@link DayAssignmentDstIT}).
 */
class DayGridDstTest {

    @ParameterizedTest
    @CsvSource({
        "2026-03-27, 2026-04-01",   // Bucharest spring change on the 29th
        "2026-10-23, 2026-10-28",   // Bucharest autumn change on the 25th
        "2026-03-06, 2026-03-10",   // US spring change on 8 March
        "2026-10-30, 2026-11-03",   // US autumn change on 1 November
    })
    void theGridHasOneRowPerCalendarDateAcrossAClockChange(LocalDate first, LocalDate last) throws IOException {
        var contract = ScoringContract.parse(DailyScorerParityTest.asContract(
                new ObjectMapper().readTree(DailyScorerParityTest.resource("adr0008", "expected.json")).get("coefficients")));
        // Only the first and last day have a row; the grid must fill every date between.
        var rows = List.of(
                new DailyScorer.DayInput(first, true, Map.of()),
                new DailyScorer.DayInput(last, true, Map.of()));

        var dates = DailyScorer.score(rows, contract).stream().map(DailyScorer.DayScore::date).toList();

        assertThat(dates).containsExactlyElementsOf(first.datesUntil(last.plusDays(1)).toList());
        assertThat(dates).doesNotHaveDuplicates();
    }
}
