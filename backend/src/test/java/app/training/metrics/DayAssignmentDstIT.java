package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import app.training.ingestion.ImportStats;
import app.training.ingestion.RawWriter;
import app.training.ingestion.garmin.GarminExportImporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * R-12: day assignment on the days the clock changes and for an athlete outside Bucharest.
 * Europe/Bucharest changes on 2026-03-29 (03:00 → 04:00) and 2026-10-25 (04:00 → 03:00, so 03:00–04:00
 * happens twice); America/New_York on 2026-03-08 and 2026-11-01. Everything is synthetic.
 * These pin down today's behaviour (ADR 0002); none of them changes it.
 */
class DayAssignmentDstIT extends IntegrationTest {
    static final List<Double> FLOORS = List.of(0.50, 0.60, 0.70, 0.80, 0.90);
    static final Trimp.Params PARAMS = new Trimp.Params(184, 48, 0.64, 1.92, FLOORS, 10);

    @Autowired GarminExportImporter garmin;
    @Autowired DayFeatureService features;

    @TempDir Path dir;

    private long athlete(String zone) {
        return jdbc.sql("INSERT INTO athlete (name, timezone) VALUES ('DST', ?) RETURNING id").param(zone).query(Long.class).single();
    }

    private static Timestamp ts(String utc) {
        return Timestamp.from(Instant.parse(utc));
    }

    private void sleep(long athlete, String zone, String startUtc, String endUtc) {
        Instant end = Instant.parse(endUtc);
        jdbc.sql("""
                INSERT INTO sleep_session (athlete_id, source, source_id, start_at, end_at, cycle_date, is_nap)
                VALUES (?, 'GARMIN', ?, ?, ?, ?, false)""")
                .params(athlete, "s-" + endUtc, ts(startUtc), ts(endUtc), LocalDate.ofInstant(end, ZoneId.of(zone))).update();
    }

    // ---- sleep_session.cycle_date through the real Garmin importer ---------------------------

    @ParameterizedTest(name = "{0} wake {1} → {2}: {3}")
    @CsvSource(delimiter = '|', textBlock = """
            # zone | sleep ends (UTC) | expected cycle date | why
            Europe/Bucharest | 2026-03-29T00:59:00Z | 2026-03-29 | 02:59 EET, the last minute before the clock jumps
            Europe/Bucharest | 2026-03-29T04:00:00Z | 2026-03-29 | 07:00 EEST, after the jump
            Europe/Bucharest | 2026-03-29T20:59:00Z | 2026-03-29 | 23:59 EEST: still the 29th
            Europe/Bucharest | 2026-03-29T21:00:00Z | 2026-03-30 | 00:00 EEST: the 30th (UTC date is still the 29th)
            Europe/Bucharest | 2026-03-28T22:00:00Z | 2026-03-29 | 00:00 EET on the 29th (UTC date is the 28th)
            Europe/Bucharest | 2026-10-25T00:30:00Z | 2026-10-25 | first 03:30 (EEST), inside the repeated hour
            Europe/Bucharest | 2026-10-25T01:30:00Z | 2026-10-25 | second 03:30 (EET), inside the repeated hour
            Europe/Bucharest | 2026-10-25T05:00:00Z | 2026-10-25 | 07:00 EET
            Europe/Bucharest | 2026-10-24T20:59:00Z | 2026-10-24 | 23:59 EEST: still the 24th
            Europe/Bucharest | 2026-10-24T21:00:00Z | 2026-10-25 | 00:00 EEST: the 25th (UTC date is still the 24th)
            Europe/Bucharest | 2026-10-25T21:59:00Z | 2026-10-25 | 23:59 EET: still the 25th
            Europe/Bucharest | 2026-10-25T22:00:00Z | 2026-10-26 | 00:00 EET: the 26th
            America/New_York | 2026-03-08T06:59:00Z | 2026-03-08 | 01:59 EST, last minute before the US jump
            America/New_York | 2026-03-08T11:00:00Z | 2026-03-08 | 07:00 EDT
            America/New_York | 2026-11-01T05:30:00Z | 2026-11-01 | first 01:30 (EDT), inside the repeated hour
            America/New_York | 2026-11-01T06:30:00Z | 2026-11-01 | second 01:30 (EST), inside the repeated hour
            America/New_York | 2026-11-01T03:59:00Z | 2026-10-31 | 23:59 EDT on 31 Oct (UTC date is 1 Nov)
            America/New_York | 2026-11-02T04:59:00Z | 2026-11-01 | 23:59 EST on 1 Nov (UTC date is 2 Nov)
            America/New_York | 2026-11-02T05:00:00Z | 2026-11-02 | 00:00 EST on 2 Nov
            """)
    void garminSleepCycleDateIsTheLocalDateOfWaking(String zone, String endUtc, LocalDate expected, String why) throws Exception {
        long a = athlete(zone);
        Instant end = Instant.parse(endUtc);
        Instant start = end.minusSeconds(8 * 3600);
        String json = """
                [{"calendarDate":"%s","sleepStartTimestampGMT":"%s","sleepEndTimestampGMT":"%s",
                  "deepSleepSeconds":6000,"lightSleepSeconds":12000,"remSleepSeconds":7200,"awakeSleepSeconds":3600}]"""
                .formatted(expected, gmt(start), gmt(end));
        Path zip = dir.resolve("garmin-" + a + ".zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("DI_CONNECT/DI-Connect-Wellness/2026-01-01_2026-12-31_123_sleepData.json"));
            z.write(json.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        long batch = jdbc.sql("INSERT INTO import_batch (athlete_id, kind, status) VALUES (?, 'GARMIN_EXPORT', 'RUNNING') RETURNING id")
                .param(a).query(Long.class).single();

        garmin.importExport(new RawWriter.Ctx(a, batch, new ImportStats()), zip, ZoneId.of(zone));

        var dates = jdbc.sql("SELECT cycle_date FROM sleep_session WHERE athlete_id = ?").param(a).query(LocalDate.class).list();
        assertThat(dates).as(why).containsExactly(expected);
    }

    private static String gmt(Instant i) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.S").format(java.time.LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC));
    }

    // ---- the view activity_with_cycle ---------------------------------------------------------

    private void activity(long athlete, String zone, String startUtc) {
        Instant start = Instant.parse(startUtc);
        jdbc.sql("""
                INSERT INTO activity (athlete_id, source, source_id, sport, start_at, calendar_date, duration_s)
                VALUES (?, 'GARMIN', ?, 'running', ?, ?, 3600)""")
                .params(athlete, "a-" + startUtc, Timestamp.from(start), LocalDate.ofInstant(start, ZoneId.of(zone))).update();
    }

    private record Placed(LocalDate cycle, LocalDate calendar, boolean fromCalendar) {}

    private Placed placed(long athlete, String startUtc) {
        return jdbc.sql("SELECT cycle_date, calendar_date, cycle_from_calendar FROM activity_with_cycle WHERE athlete_id = ? AND start_at = ?")
                .params(athlete, ts(startUtc))
                .query((rs, n) -> new Placed(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class), rs.getBoolean(3))).single();
    }

    @Test
    void anActivityBetweenMidnightAndWakingBelongsToThePreviousDayAcrossTheSpringChange() {
        long a = athlete("Europe/Bucharest");
        sleep(a, "Europe/Bucharest", "2026-03-28T20:00:00Z", "2026-03-29T04:00:00Z");   // wake 07:00 EEST on the 29th
        sleep(a, "Europe/Bucharest", "2026-03-29T20:30:00Z", "2026-03-30T04:00:00Z");   // next wake 07:00 EEST on the 30th
        activity(a, "Europe/Bucharest", "2026-03-29T21:30:00Z");                         // 00:30 EEST on the 30th, before waking
        activity(a, "Europe/Bucharest", "2026-03-30T04:30:00Z");                         // 07:30 EEST, after waking
        assertThat(placed(a, "2026-03-29T21:30:00Z")).isEqualTo(new Placed(LocalDate.parse("2026-03-29"), LocalDate.parse("2026-03-30"), false));
        assertThat(placed(a, "2026-03-30T04:30:00Z")).isEqualTo(new Placed(LocalDate.parse("2026-03-30"), LocalDate.parse("2026-03-30"), false));
    }

    @Test
    void anActivityInTheRepeatedHourBelongsToThePreviousDay() {
        long a = athlete("Europe/Bucharest");
        sleep(a, "Europe/Bucharest", "2026-10-23T20:00:00Z", "2026-10-24T04:00:00Z");   // wake 07:00 EEST on the 24th
        sleep(a, "Europe/Bucharest", "2026-10-24T20:00:00Z", "2026-10-25T05:00:00Z");   // wake 07:00 EET on the 25th
        activity(a, "Europe/Bucharest", "2026-10-25T00:30:00Z");                         // first 03:30 (EEST)
        activity(a, "Europe/Bucharest", "2026-10-25T01:30:00Z");                         // second 03:30 (EET)
        activity(a, "Europe/Bucharest", "2026-10-25T05:30:00Z");                         // 07:30 EET, after waking
        var prev = new Placed(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25"), false);
        assertThat(placed(a, "2026-10-25T00:30:00Z")).isEqualTo(prev);
        assertThat(placed(a, "2026-10-25T01:30:00Z")).isEqualTo(prev);
        assertThat(placed(a, "2026-10-25T05:30:00Z")).isEqualTo(new Placed(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-25"), false));
    }

    @Test
    void theThirtySixHourWindowIsRealElapsedTimeNotWallClockTimeAcrossTheSpringChange() {
        long a = athlete("Europe/Bucharest");
        sleep(a, "Europe/Bucharest", "2026-03-27T21:00:00Z", "2026-03-28T05:00:00Z");   // wake 07:00 EET on the 28th
        // 36 h of real time later is 2026-03-29T17:00Z. One minute inside the window, one minute outside:
        activity(a, "Europe/Bucharest", "2026-03-29T16:59:00Z");
        activity(a, "Europe/Bucharest", "2026-03-29T17:01:00Z");
        assertThat(placed(a, "2026-03-29T16:59:00Z")).isEqualTo(new Placed(LocalDate.parse("2026-03-28"), LocalDate.parse("2026-03-29"), false));
        // No sleep within 36 h: falls back to the calendar date and says so.
        assertThat(placed(a, "2026-03-29T17:01:00Z")).isEqualTo(new Placed(LocalDate.parse("2026-03-29"), LocalDate.parse("2026-03-29"), true));
    }

    @Test
    void theNewYorkAthleteGetsTheSameRulesOnTheUsChange() {
        long a = athlete("America/New_York");
        sleep(a, "America/New_York", "2026-10-31T03:00:00Z", "2026-10-31T11:00:00Z");   // wake 07:00 EDT on 31 Oct
        sleep(a, "America/New_York", "2026-11-01T03:00:00Z", "2026-11-01T11:00:00Z");   // wake 06:00 EST on 1 Nov (clock went back at 06:00Z)
        activity(a, "America/New_York", "2026-11-01T05:30:00Z");   // first 01:30 (EDT) on 1 Nov, before that wake
        activity(a, "America/New_York", "2026-11-02T04:30:00Z");   // 23:30 EST on 1 Nov (UTC date is the 2nd)
        assertThat(placed(a, "2026-11-01T05:30:00Z")).isEqualTo(new Placed(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-01"), false));
        assertThat(placed(a, "2026-11-02T04:30:00Z")).isEqualTo(new Placed(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"), false));
    }

    // ---- DayFeatureService.backgroundLoad, seen through recompute -----------------------------

    /** Six samples one minute apart starting at {@code startUtc}: five covered minutes. */
    private void monitoring(long athlete, String startUtc) {
        Instant t = Instant.parse(startUtc);
        for (int i = 0; i < 6; i++) {
            jdbc.sql("INSERT INTO monitoring_hr (athlete_id, ts, hr) VALUES (?, ?, 120)").params(athlete, Timestamp.from(t.plusSeconds(60L * i))).update();
        }
    }

    /** Covered minutes per cycle day, for days with any monitoring. */
    private Map<LocalDate, Integer> monitoredMinutes(long athlete, String zone) {
        features.recompute(athlete, PARAMS, ZoneId.of(zone));
        var out = new TreeMap<LocalDate, Integer>();
        jdbc.sql("SELECT cycle_date, monitored_minutes FROM day_features WHERE athlete_id = ? AND monitored_minutes > 0")
                .param(athlete).query((rs, n) -> out.put(rs.getObject(1, LocalDate.class), rs.getInt(2))).list();
        return out;
    }

    @Test
    void allDayHeartRateBeforeWakingGoesToThePreviousDayAcrossTheSpringChange() {
        long a = athlete("Europe/Bucharest");
        sleep(a, "Europe/Bucharest", "2026-03-27T20:00:00Z", "2026-03-28T05:00:00Z");   // wake 07:00 EET on the 28th
        sleep(a, "Europe/Bucharest", "2026-03-28T20:00:00Z", "2026-03-29T04:00:00Z");   // wake 07:00 EEST on the 29th
        monitoring(a, "2026-03-28T22:30:00Z");   // 00:30 EET on the 29th, before the jump
        monitoring(a, "2026-03-29T01:30:00Z");   // 04:30 EEST, after the jump, still asleep
        monitoring(a, "2026-03-29T05:00:00Z");   // 08:00 EEST, awake
        assertThat(monitoredMinutes(a, "Europe/Bucharest")).containsExactly(
                Map.entry(LocalDate.parse("2026-03-28"), 10), Map.entry(LocalDate.parse("2026-03-29"), 5));
    }

    @Test
    void allDayHeartRateInTheRepeatedHourGoesToThePreviousDay() {
        long a = athlete("Europe/Bucharest");
        sleep(a, "Europe/Bucharest", "2026-10-23T20:00:00Z", "2026-10-24T04:00:00Z");   // wake 07:00 EEST on the 24th
        sleep(a, "Europe/Bucharest", "2026-10-24T20:00:00Z", "2026-10-25T05:00:00Z");   // wake 07:00 EET on the 25th
        monitoring(a, "2026-10-25T00:30:00Z");   // first 03:30
        monitoring(a, "2026-10-25T01:30:00Z");   // second 03:30
        monitoring(a, "2026-10-25T06:00:00Z");   // 08:00 EET, awake
        assertThat(monitoredMinutes(a, "Europe/Bucharest")).containsExactly(
                Map.entry(LocalDate.parse("2026-10-24"), 10), Map.entry(LocalDate.parse("2026-10-25"), 5));
    }

    @Test
    void withoutASleepTheAthletesLocalCalendarDateIsUsedNotTheUtcDate() {
        long bucharest = athlete("Europe/Bucharest");
        monitoring(bucharest, "2026-03-29T20:30:00Z");   // 23:30 EEST on the 29th
        monitoring(bucharest, "2026-03-29T21:30:00Z");   // 00:30 EEST on the 30th (UTC date is still the 29th)
        monitoring(bucharest, "2026-10-25T21:30:00Z");   // 23:30 EET on the 25th
        monitoring(bucharest, "2026-10-24T21:30:00Z");   // 00:30 EEST on the 25th (UTC date is the 24th)
        assertThat(monitoredMinutes(bucharest, "Europe/Bucharest")).containsOnly(
                Map.entry(LocalDate.parse("2026-03-29"), 5), Map.entry(LocalDate.parse("2026-03-30"), 5),
                Map.entry(LocalDate.parse("2026-10-25"), 10));

        long newYork = athlete("America/New_York");
        monitoring(newYork, "2026-03-09T03:30:00Z");   // 23:30 EDT on 8 Mar (UTC date is the 9th)
        monitoring(newYork, "2026-11-02T04:30:00Z");   // 23:30 EST on 1 Nov (UTC date is the 2nd)
        assertThat(monitoredMinutes(newYork, "America/New_York")).containsOnly(
                Map.entry(LocalDate.parse("2026-03-08"), 5), Map.entry(LocalDate.parse("2026-11-01"), 5));
    }
}
