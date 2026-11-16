package app.training.ingestion.whoop;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.ingestion.TimeParsing;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * R-12: a Whoop cycle belongs to the local date of waking (ADR 0002) on the days the clock
 * changes, in the repeated hour, and for an athlete far from Bucharest. All times are synthetic.
 * Instants are written in UTC so the expected local time can be checked by hand (see "why").
 */
class CycleDateDstTest {

    @ParameterizedTest(name = "{0}: {5}")
    @CsvSource(delimiter = '|', textBlock = """
            # zone | cycle start (UTC) | wake onset (UTC) | expected date | why
            Europe/Bucharest | 2026-03-28T21:00:00Z | 2026-03-29T04:30:00Z | 2026-03-29 | spring: start 23:00 EET, wake 07:30 EEST, 7.5 h of real sleep
            Europe/Bucharest | 2026-03-28T21:00:00Z |                      | 2026-03-29 | spring, no wake onset: start + 12 h = 12:00 EEST
            Europe/Bucharest | 2026-03-29T21:30:00Z | 2026-03-30T05:00:00Z | 2026-03-30 | night after the change: start 00:30 EEST on the 30th
            Europe/Bucharest | 2026-10-24T20:30:00Z | 2026-10-25T05:00:00Z | 2026-10-25 | autumn: start 23:30 EEST, wake 07:00 EET, 8.5 h
            Europe/Bucharest | 2026-10-24T20:30:00Z |                      | 2026-10-25 | autumn, no wake onset: start + 12 h = 10:30 EET
            Europe/Bucharest | 2026-10-24T20:00:00Z | 2026-10-25T01:30:00Z | 2026-10-25 | wake inside the repeated hour (second 03:30, EET)
            Europe/Bucharest | 2026-10-24T20:00:00Z | 2026-10-25T00:30:00Z | 2026-10-25 | wake inside the repeated hour (first 03:30, EEST)
            Europe/Bucharest | 2026-10-25T00:30:00Z |                      | 2026-10-25 | cycle starts in the repeated hour (first 03:30)
            Europe/Bucharest | 2026-10-25T01:30:00Z |                      | 2026-10-25 | cycle starts in the repeated hour (second 03:30)
            America/New_York | 2026-03-08T03:00:00Z | 2026-03-08T12:00:00Z | 2026-03-08 | US spring: start 22:00 EST on the 7th, wake 08:00 EDT on the 8th
            America/New_York | 2026-11-01T03:00:00Z | 2026-11-01T12:00:00Z | 2026-11-01 | US autumn: start 23:00 EDT on 31 Oct, wake 07:00 EST on 1 Nov
            America/New_York | 2026-11-01T05:30:00Z | 2026-11-01T06:30:00Z | 2026-11-01 | sleep from the first 01:30 EDT to the second 01:30 EST, wake on 1 Nov
            America/New_York | 2026-11-01T20:30:00Z | 2026-11-02T04:30:00Z | 2026-11-01 | wake 23:30 EST on 1 Nov: the local date, not the UTC date (2 Nov)
            America/New_York | 2026-11-02T03:00:00Z |                      | 2026-11-02 | no wake onset: start 22:00 EST, + 12 h = 10:00 EST on 2 Nov
            """)
    void cycleBelongsToTheLocalWakeDay(String zone, Instant start, Instant wake, LocalDate expected, String why) {
        assertThat(WhoopCsvImporter.cycleDate(start, wake, ZoneId.of(zone))).as(why).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            # Whoop writes "Cycle timezone" as a fixed offset that already includes the daylight-saving shift of that day.
            UTC+03:00 | 2026-03-28 23:00:00 | 2026-03-29 07:30:00 | 2026-03-29
            UTC+02:00 | 2026-10-24 23:30:00 | 2026-10-25 07:00:00 | 2026-10-25
            UTC-05:00 | 2026-10-31 23:00:00 | 2026-11-01 07:00:00 | 2026-11-01
            """)
    void aCycleWithAFixedOffsetTimezoneColumnKeepsItsLocalDate(String tz, String start, String wake, LocalDate expected) {
        ZoneId fallback = ZoneId.of("Europe/Bucharest");
        ZoneId z = TimeParsing.zone(tz, fallback);
        assertThat(WhoopCsvImporter.cycleDate(TimeParsing.instant(start, z), TimeParsing.instant(wake, z), z)).isEqualTo(expected);
    }
}
