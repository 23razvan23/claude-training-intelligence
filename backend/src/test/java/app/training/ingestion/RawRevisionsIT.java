package app.training.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import app.training.metrics.DayFeatureService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * R-10 / ADR 0010: a Garmin day fetched again (a later export, later the morning sync) replaces
 * the earlier, partial version in what the features read, while raw rows stay append-only and an
 * identical re-import still inserts nothing.
 */
class RawRevisionsIT extends IntegrationTest {
    static final LocalDate DAY = LocalDate.parse("2026-11-09");

    @Autowired RawWriter writer;
    @Autowired DayFeatureService features;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    long athlete;

    @BeforeEach
    void setUp() {
        athlete = jdbc.sql("INSERT INTO athlete (name, timezone) VALUES ('R', 'UTC') RETURNING id").query(Long.class).single();
    }

    /** A new import batch, as each export upload is. */
    private RawWriter.Ctx batch() {
        long id = jdbc.sql("INSERT INTO import_batch (athlete_id, kind, status) VALUES (?, 'GARMIN_EXPORT', 'RUNNING') RETURNING id")
                .param(athlete).query(Long.class).single();
        return new RawWriter.Ctx(athlete, id, new ImportStats());
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    /** Garmin's main sleep for the night before DAY (source id = Garmin's calendarDate). */
    private static RawRows.Sleep sleep(String start, String end, int lightS) {
        return new RawRows.Sleep(RawRows.GARMIN, DAY.toString(), at(start), at(end), DAY, false, 3600, lightS, 5400, 600, 80,
                new BigDecimal("14.0"), null, null, null);
    }

    private static RawRows.Wellness summary(Integer restingHr, Integer bodyBatteryHigh, Integer stress) {
        return new RawRows.Wellness(RawRows.GARMIN, "uds", DAY, restingHr, null, null, null, null, stress, bodyBatteryHigh, 20);
    }

    private Map<String, Object> featureRow() {
        features.recompute(athlete, null, ZoneOffset.UTC);
        return jdbc.sql("SELECT * FROM day_features WHERE athlete_id = ? AND cycle_date = ?").params(athlete, DAY).query().singleRow();
    }

    @Test
    void aShiftedLaterVersionOfTheNightReplacesTheFirstInsteadOfBeingAdded() {
        writer.sleep(batch(), sleep("2026-11-08T23:00:00Z", "2026-11-09T07:00:00Z", 14_400));   // 390 min asleep
        writer.sleep(batch(), sleep("2026-11-08T23:04:00Z", "2026-11-09T07:24:00Z", 15_600));   // 410 min, starts 4 min later
        assertThat(featureRow().get("sleep_minutes")).isEqualTo(410);
    }

    @Test
    void aLaterVersionWithTheSameStartIsKeptAsARevisionAndUsed() {
        writer.sleep(batch(), sleep("2026-11-08T23:00:00Z", "2026-11-09T07:00:00Z", 14_400));
        var second = batch();
        writer.sleep(second, sleep("2026-11-08T23:00:00Z", "2026-11-09T07:20:00Z", 15_600));
        assertThat(second.stats().inserted("sleep_session")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", athlete)).isEqualTo(2); // raw keeps both
        assertThat(featureRow().get("sleep_minutes")).isEqualTo(410);
    }

    @Test
    void aLaterDailySummaryReplacesTheEarlierPartialOne() {
        writer.wellness(batch(), summary(47, 85, 24));   // exported during the day
        writer.wellness(batch(), summary(45, 85, 31));   // the finished day, from a later export
        var row = featureRow();
        assertThat(row.get("rhr")).isEqualTo(45);
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ?", athlete)).isEqualTo(2);
    }

    @Test
    void aValueMissingFromTheLaterVersionKeepsTheEarlierOne() {
        writer.wellness(batch(), summary(47, 85, 24));
        writer.wellness(batch(), summary(45, null, 31));
        var row = featureRow();
        assertThat(row.get("rhr")).isEqualTo(45);
        assertThat(row.get("body_battery_high")).isEqualTo(85);
    }

    @Test
    void theDailySummaryAndTheOvernightHrvStillCombine() {
        writer.wellness(batch(), summary(47, 85, 24));
        writer.wellness(batch(), new RawRows.Wellness(RawRows.GARMIN, "healthStatus", DAY, null, new BigDecimal("68.0"), "BALANCED",
                null, null, null, null, null));
        writer.wellness(batch(), summary(45, 85, 31));
        var row = featureRow();
        assertThat(row.get("rhr")).isEqualTo(45);
        assertThat(((BigDecimal) row.get("rmssd")).doubleValue()).isEqualTo(68.0);
    }

    @Test
    void anIdenticalReimportInsertsNothingAndChangesNoFeature() {
        writer.wellness(batch(), summary(47, 85, 24));
        writer.sleep(batch(), sleep("2026-11-08T23:00:00Z", "2026-11-09T07:00:00Z", 14_400));
        writer.wellness(batch(), summary(45, 85, 31));
        writer.sleep(batch(), sleep("2026-11-08T23:00:00Z", "2026-11-09T07:20:00Z", 15_600));
        var before = featureRow();
        long rows = count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ?", athlete)
                + count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", athlete);

        // The same export again: the newest versions, unchanged.
        var again = batch();
        writer.wellness(again, summary(45, 85, 31));
        writer.sleep(again, sleep("2026-11-08T23:00:00Z", "2026-11-09T07:20:00Z", 15_600));
        assertThat(again.stats().insertedTotal()).isZero();
        assertThat(again.stats().counters().get("daily_wellness").duplicates()).isEqualTo(1);
        assertThat(again.stats().counters().get("sleep_session").duplicates()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ?", athlete)
                + count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", athlete)).isEqualTo(rows);

        var after = featureRow();
        for (String c : new String[] {"rhr", "rmssd", "body_battery_high", "sleep_minutes", "sleep_respiration"}) {
            assertThat(after.get(c)).as(c).isEqualTo(before.get(c));
        }
    }

    @Test
    void reuploadingAnOlderExportChangesNothing() {
        writer.wellness(batch(), summary(47, 85, 24));
        writer.sleep(batch(), sleep("2026-11-08T23:00:00Z", "2026-11-09T07:00:00Z", 14_400));
        writer.wellness(batch(), summary(45, 85, 31));
        writer.sleep(batch(), sleep("2026-11-08T23:04:00Z", "2026-11-09T07:24:00Z", 15_600));
        // The first export by mistake again: every record matches a stored version, so it is a duplicate.
        var old = batch();
        writer.wellness(old, summary(47, 85, 24));
        writer.sleep(old, sleep("2026-11-08T23:00:00Z", "2026-11-09T07:00:00Z", 14_400));
        assertThat(old.stats().insertedTotal()).isZero();
        var row = featureRow();
        assertThat(row.get("rhr")).isEqualTo(45);
        assertThat(row.get("sleep_minutes")).isEqualTo(410);
    }

    @Test
    void aNightSplitIntoTwoRecordsInOneExportIsStillReadWhole() {
        // Two Garmin records in the same import (one transaction) for one night: both count, as before.
        var one = batch();
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status -> {
            writer.sleep(one, new RawRows.Sleep(RawRows.GARMIN, DAY.toString(), at("2026-11-08T22:00:00Z"), at("2026-11-09T01:00:00Z"),
                    DAY, false, 1800, 7200, 1800, 300, null, null, null, null, null));
            writer.sleep(one, new RawRows.Sleep(RawRows.GARMIN, DAY.toString(), at("2026-11-09T02:00:00Z"), at("2026-11-09T07:00:00Z"),
                    DAY, false, 3600, 10_800, 3600, 300, null, null, null, null, null));
        });
        assertThat(featureRow().get("sleep_minutes")).isEqualTo(180 + 300);
    }

    @Test
    void aWorkoutBelongsToTheCycleOfTheNewestVersionOfTheNight() {
        // The first version of the night ends at 07:00, the newer one at 07:20; a 07:10 run starts after
        // the first but before the second: it belongs to the newest night's cycle only if that one ended before it.
        writer.sleep(batch(), sleep("2026-11-08T23:00:00Z", "2026-11-09T07:00:00Z", 14_400));
        writer.sleep(batch(), sleep("2026-11-08T23:00:00Z", "2026-11-09T07:20:00Z", 15_600));
        long id = writer.activity(batch(), new RawRows.Activity(RawRows.GARMIN, "run-1", "running", "running",
                at("2026-11-09T07:10:00Z"), DAY, 1800, new BigDecimal("5000"), null, 140, 160, null, null, null, null, null));
        var cycle = jdbc.sql("SELECT cycle_date, cycle_from_calendar FROM activity_with_cycle WHERE id = ?").param(id).query().singleRow();
        // Only the old (superseded) version ended before 07:10: the newest night has not ended, so the
        // workout falls back to its calendar date instead of being placed by a stale sleep.
        assertThat(cycle.get("cycle_from_calendar")).isEqualTo(true);
    }
}
