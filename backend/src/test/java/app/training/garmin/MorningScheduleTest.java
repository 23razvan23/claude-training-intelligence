package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** When the morning run is due (ADR 0015 D9), with a fake clock. */
class MorningScheduleTest {
    static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    static final LocalTime SEVEN = LocalTime.of(7, 0);

    private static Instant at(String local, ZoneId zone) {
        return java.time.LocalDateTime.parse(local).atZone(zone).toInstant();
    }

    @Test
    void notBeforeSevenThenDueAtSeven() {
        assertThat(MorningSchedule.due(at("2026-11-18T06:59:59", BUCHAREST), BUCHAREST, SEVEN, null)).isFalse();
        assertThat(MorningSchedule.due(at("2026-11-18T07:00:00", BUCHAREST), BUCHAREST, SEVEN, null)).isTrue();
    }

    @Test
    void aMissedSevenIsCaughtUpLaterTheSameDay() {
        // Yesterday's morning run happened; the Mac slept through 07:00 today and wakes at 14:00.
        Instant yesterday = at("2026-11-17T07:00:30", BUCHAREST);
        assertThat(MorningSchedule.due(at("2026-11-18T14:00:00", BUCHAREST), BUCHAREST, SEVEN, yesterday)).isTrue();
        assertThat(MorningSchedule.due(at("2026-11-18T23:59:00", BUCHAREST), BUCHAREST, SEVEN, yesterday)).isTrue();
    }

    @Test
    void noDoubleRunOnceTodaysRunStarted() {
        Instant ran = at("2026-11-18T07:00:20", BUCHAREST);
        assertThat(MorningSchedule.due(at("2026-11-18T07:01:00", BUCHAREST), BUCHAREST, SEVEN, ran)).isFalse();
        assertThat(MorningSchedule.due(at("2026-11-18T22:00:00", BUCHAREST), BUCHAREST, SEVEN, ran)).isFalse();
        // The next day it is due again.
        assertThat(MorningSchedule.due(at("2026-11-19T07:00:00", BUCHAREST), BUCHAREST, SEVEN, ran)).isTrue();
    }

    @Test
    void aRunBeforeSevenDoesNotCountForToday() {
        // A Sync now at 06:30 is not the morning run: Garmin may not have the night yet.
        Instant early = at("2026-11-18T06:30:00", BUCHAREST);
        assertThat(MorningSchedule.due(at("2026-11-18T07:00:00", BUCHAREST), BUCHAREST, SEVEN, early)).isTrue();
    }

    @Test
    void afterMidnightTheMissedDayIsNotCaughtUp() {
        // The Mac slept all of the 18th; at 00:30 on the 19th the next run waits for 07:00.
        Instant ran = at("2026-11-17T07:00:00", BUCHAREST);
        assertThat(MorningSchedule.due(at("2026-11-19T00:30:00", BUCHAREST), BUCHAREST, SEVEN, ran)).isFalse();
        assertThat(MorningSchedule.due(at("2026-11-19T07:00:00", BUCHAREST), BUCHAREST, SEVEN, ran)).isTrue();
    }

    @Test
    void springForwardDayInBucharestStartsAtSevenLocal() {
        // 2026-03-29: clocks go 03:00 -> 04:00; 07:00 local is 04:00 UTC (EEST, +3).
        Instant start = MorningSchedule.startOfToday(at("2026-03-29T12:00:00", BUCHAREST), BUCHAREST, SEVEN);
        assertThat(start).isEqualTo(Instant.parse("2026-03-29T04:00:00Z"));
        assertThat(MorningSchedule.due(Instant.parse("2026-03-29T03:59:00Z"), BUCHAREST, SEVEN, null)).isFalse();
        assertThat(MorningSchedule.due(Instant.parse("2026-03-29T04:00:00Z"), BUCHAREST, SEVEN, null)).isTrue();
        // The day before, 07:00 local was 05:00 UTC (EET, +2): one run each day, none skipped.
        Instant dayBefore = Instant.parse("2026-03-28T05:00:00Z");
        assertThat(MorningSchedule.due(Instant.parse("2026-03-29T04:00:00Z"), BUCHAREST, SEVEN, dayBefore)).isTrue();
    }

    @Test
    void fallBackDayInBucharestStartsAtSevenLocalAndRunsOnce() {
        // 2026-10-25: clocks go 04:00 -> 03:00; 07:00 local is 05:00 UTC (EET, +2).
        Instant start = MorningSchedule.startOfToday(at("2026-10-25T12:00:00", BUCHAREST), BUCHAREST, SEVEN);
        assertThat(start).isEqualTo(Instant.parse("2026-10-25T05:00:00Z"));
        assertThat(MorningSchedule.due(Instant.parse("2026-10-25T04:59:00Z"), BUCHAREST, SEVEN, null)).isFalse();
        Instant ran = Instant.parse("2026-10-25T05:00:10Z");
        // The repeated hour happens before 07:00, so it can't cause a second run.
        assertThat(MorningSchedule.due(Instant.parse("2026-10-25T06:00:00Z"), BUCHAREST, SEVEN, ran)).isFalse();
    }

    @Test
    void anotherTimezoneUsesItsOwnSeven() {
        // America/New_York on its fall-back day (2026-11-01): 07:00 EST = 12:00 UTC.
        assertThat(MorningSchedule.startOfToday(Instant.parse("2026-11-01T15:00:00Z"), NEW_YORK, SEVEN))
                .isEqualTo(Instant.parse("2026-11-01T12:00:00Z"));
        // At 09:00 UTC it is 04:00 in New York: not due, although it's past 07:00 in Bucharest.
        assertThat(MorningSchedule.due(Instant.parse("2026-11-18T09:00:00Z"), NEW_YORK, SEVEN, null)).isFalse();
        assertThat(MorningSchedule.due(Instant.parse("2026-11-18T09:00:00Z"), BUCHAREST, SEVEN, null)).isTrue();
    }

    @Test
    void aTimeInsideTheSpringGapMovesForward() {
        // A configured 03:30 doesn't exist in Bucharest on 2026-03-29; it becomes 04:30 local (01:30 UTC).
        Instant start = MorningSchedule.startOfToday(at("2026-03-29T12:00:00", BUCHAREST), BUCHAREST, LocalTime.of(3, 30));
        assertThat(start).isEqualTo(ZonedDateTime.of(2026, 3, 29, 4, 30, 0, 0, BUCHAREST).toInstant());
    }
}
