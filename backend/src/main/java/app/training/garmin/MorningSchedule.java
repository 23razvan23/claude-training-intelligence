package app.training.garmin;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When the morning sync is due (ADR 0015 D9), as pure arithmetic so it can be tested with a fake
 * clock: once a day, from the configured time in the athlete's timezone (07:00 by default). If the
 * Mac was asleep at that time, the run is due as soon as it is awake again, the same day.
 *
 * <p>"Already done today" means a run that counts as the morning run started on or after today's
 * start time: any morning run (whatever its result: a morning run that failed after its retries is
 * not started again), or a Sync now that succeeded (coach, Q2 of the PR 3 plan).
 */
public final class MorningSchedule {
    private MorningSchedule() {}

    /**
     * Today's start time as an instant. On a day when the clocks change, the local time is read
     * in that day's offset; a time that doesn't exist (inside a spring-forward gap) moves forward
     * by the gap, as {@link ZonedDateTime#of} does.
     */
    public static Instant startOfToday(Instant now, ZoneId zone, LocalTime at) {
        return ZonedDateTime.of(now.atZone(zone).toLocalDate(), at, zone).toInstant();
    }

    /**
     * @param lastCounting the start of the latest run that counts as a morning run (null: none ever)
     * @return true when the morning run should start now
     */
    public static boolean due(Instant now, ZoneId zone, LocalTime at, Instant lastCounting) {
        Instant start = startOfToday(now, zone, at);
        if (now.isBefore(start)) return false;
        return lastCounting == null || lastCounting.isBefore(start);
    }
}
