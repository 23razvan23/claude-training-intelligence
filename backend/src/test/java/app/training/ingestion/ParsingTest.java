package app.training.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.training.ingestion.whoop.WhoopColumns;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParsingTest {
    static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");

    @Test
    void zonesInEveryExportSpelling() {
        assertThat(TimeParsing.zone("UTC+02:00", BUCHAREST)).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(TimeParsing.zone("UTC-05:30", BUCHAREST)).isEqualTo(ZoneOffset.ofHoursMinutes(-5, -30));
        assertThat(TimeParsing.zone("+0300", BUCHAREST)).isEqualTo(ZoneOffset.ofHours(3));
        assertThat(TimeParsing.zone("Europe/London", BUCHAREST)).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(TimeParsing.zone("UTC", BUCHAREST)).isEqualTo(ZoneOffset.UTC);
        assertThat(TimeParsing.zone("", BUCHAREST)).isEqualTo(BUCHAREST);
        assertThat(TimeParsing.zone("Mars/Olympus", BUCHAREST)).isEqualTo(BUCHAREST);
    }

    @Test
    void instantsLocalAndOffset() {
        assertThat(TimeParsing.instant("2024-03-01 07:00:00", ZoneOffset.ofHours(2)))
                .isEqualTo(Instant.parse("2024-03-01T05:00:00Z"));
        assertThat(TimeParsing.instant("2024-03-01T07:00", BUCHAREST))
                .isEqualTo(Instant.parse("2024-03-01T05:00:00Z"));
        assertThat(TimeParsing.instant("2024-03-01T07:00:00.5+03:00", BUCHAREST))
                .isEqualTo(Instant.parse("2024-03-01T04:00:00.5Z"));
        assertThat(TimeParsing.instant("  ", BUCHAREST)).isNull();
        assertThatThrownBy(() -> TimeParsing.instant("yesterday", BUCHAREST)).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void csvHeadersMatchTolerantly() throws Exception {
        var t = CsvTable.read(new StringReader("﻿cycle_start_time,RECOVERY SCORE,Day Strain\n2024-03-01 07:00:00,55%,\"1,2.5\"\n"));
        var r = t.records().getFirst();
        assertThat(t.text(r, WhoopColumns.CYCLE_START)).isEqualTo("2024-03-01 07:00:00");
        assertThat(t.integer(r, WhoopColumns.RECOVERY)).isEqualTo(55);
        assertThat(t.decimal(r, WhoopColumns.DAY_STRAIN)).isEqualByComparingTo("12.5");
        assertThat(t.integer(r, WhoopColumns.RESTING_HR)).isNull();
        assertThat(WhoopColumns.FileType.detect(t)).isEqualTo(WhoopColumns.FileType.CYCLES);
    }

    @Test
    void sportNames() {
        assertThat(SportNames.normalize("RUNNING")).isEqualTo("running");
        assertThat(SportNames.normalize("trail_running")).isEqualTo("running");
        assertThat(SportNames.normalize("Cycling")).isEqualTo("cycling");
        assertThat(SportNames.normalize("Functional Fitness")).isEqualTo("strength");
        assertThat(SportNames.normalize(null)).isEqualTo("other");
    }

    @Test
    void gapsAreContiguousUnpairedRuns() {
        LocalDate d = LocalDate.of(2024, 1, 1);
        var paired = new CoverageService.Day(d, true, true, true, 0, true, true, true);
        var unpaired = new CoverageService.Day(d, true, false, true, 0, true, true, true);
        var days = List.of(
                withDate(unpaired, d), withDate(paired, d.plusDays(1)), withDate(unpaired, d.plusDays(2)),
                withDate(unpaired, d.plusDays(3)), withDate(paired, d.plusDays(4)));
        assertThat(CoverageService.gaps(days)).containsExactly(
                new CoverageService.Gap(d, d, 1), new CoverageService.Gap(d.plusDays(2), d.plusDays(3), 2));
    }

    private static CoverageService.Day withDate(CoverageService.Day x, LocalDate d) {
        return new CoverageService.Day(d, x.garminRestingHr(), x.garminHrv(), x.garminSleep(), x.garminActivities(),
                x.whoopRecovery(), x.whoopStrain(), x.whoopSleep());
    }
}
