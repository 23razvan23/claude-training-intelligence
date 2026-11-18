package app.training.adaptation.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import app.training.ingestion.ImportBatchRepository;
import app.training.ingestion.ImportKind;
import app.training.ingestion.ImportRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** ADR 0020 amendment: the Garmin effort fields are stored, and seed the check-in history once, never over the athlete's own answer. */
class GarminSeedIT extends IntegrationTest {
    @Autowired ImportRunner runner;
    @Autowired ImportBatchRepository batches;
    @Autowired GarminSeedService seed;
    @Autowired CheckinRepository checkins;

    @TempDir Path dir;

    // ---------------------------------------------------------------- helpers

    private static final long BASE_MS = Instant0.epoch("2026-11-10T07:00:00Z");

    /** Tiny stand-in for java.time so the fixture reads as data. */
    private static final class Instant0 {
        static long epoch(String iso) {
            return java.time.Instant.parse(iso).toEpochMilli();
        }
    }

    private static String act(long id, long startMs, long localMs, String effort) {
        return """
                {"activityId":%d,"activityType":"running","startTimeGmt":%d.0,"startTimeLocal":%d.0,
                 "duration":3600000.0,"distance":1000000.0,"avgHr":145,"maxHr":160%s}""".formatted(id, startMs, localMs, effort);
    }

    /** A Garmin export holding only activity summaries. */
    private Path export(String name, String... activities) throws Exception {
        Path zip = dir.resolve(name + ".zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("DI_CONNECT/DI-Connect-Fitness/x_summarizedActivities.json"));
            z.write(("[{\"summarizedActivitiesExport\":[" + String.join(",", activities) + "]}]").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return zip;
    }

    private void importExport(long athlete, Path zip) throws Exception {
        Path copy = Files.createTempFile(dir, "upload", ".zip");   // the runner deletes its input
        Files.copy(zip, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        long user = jdbc.sql("INSERT INTO app_user (username, password_hash, role) VALUES ('c' || nextval('app_user_id_seq'), 'x', 'COACH') RETURNING id")
                .query(Long.class).single();
        long id = batches.create(athlete, ImportKind.GARMIN_EXPORT, "test", null, user);
        runner.run(id, athlete, ImportKind.GARMIN_EXPORT, List.of(copy), null);
        assertThat(batches.find(id).orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    private long athlete(String name) {
        return jdbc.sql("INSERT INTO athlete (name, timezone) VALUES (?, 'Europe/Bucharest') RETURNING id").param(name).query(Long.class).single();
    }

    private long activityOf(long athlete, long garminId) {
        return jdbc.sql("SELECT id FROM activity WHERE athlete_id = ? AND source_id = ?").params(athlete, String.valueOf(garminId)).query(Long.class).single();
    }

    private long seedRows(long athlete) {
        return count("SELECT count(*) FROM subjective_log WHERE athlete_id = ? AND source = 'GARMIN_SEED'", athlete);
    }

    private List<CheckinView> current(long athlete) {
        return checkins.current(athlete, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    }

    private long manualCheckin(long athlete, long activityId, LocalDate date, int rpe) {
        return checkins.insert(athlete, date, new CheckinRequest(null, null, activityId, rpe, null, null, null, false, false, false, null), null, false);
    }

    // ---------------------------------------------------------------- import: values are stored and seeded

    @Test
    void anExportStoresTheEffortAndSeedsOneCheckinPerActivityWithARpe() throws Exception {
        long a = athlete("Seed import");
        long late = BASE_MS + 15L * 3600_000;                       // 22:00 UTC = 00:00 next local day in Bucharest (UTC+2)
        importExport(a, export("one",
                act(9001, BASE_MS, BASE_MS + 2 * 3600_000, ",\"workoutRpe\":30.0,\"workoutFeel\":75.0"),
                act(9002, late, late + 2 * 3600_000, ",\"workoutRpe\":60,\"workoutFeel\":50"),
                act(9003, BASE_MS + 86_400_000, BASE_MS + 86_400_000 + 2 * 3600_000, ",\"workoutRpe\":0,\"workoutFeel\":100"),
                act(9004, BASE_MS + 2 * 86_400_000, BASE_MS + 2 * 86_400_000 + 2 * 3600_000, "")));

        assertThat(count("SELECT count(*) FROM activity_effort e JOIN activity a ON a.id = e.activity_id WHERE a.athlete_id = ?", a)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT workout_rpe FROM activity_effort WHERE activity_id = ?").param(activityOf(a, 9002)).query(Integer.class).single())
                .isEqualTo(60);   // stored as received
        // 0 means "not set" and activity 9004 has no effort: only 9001 and 9002 are seeded.
        assertThat(seedRows(a)).isEqualTo(2);
        var rows = jdbc.sql("""
                SELECT s.rpe, s.log_date, s.created_by, s.on_behalf, s.planned_session_id, a.calendar_date, a.source_id
                FROM subjective_log s JOIN activity a ON a.id = s.activity_id WHERE s.athlete_id = ? ORDER BY a.source_id""")
                .param(a).query().listOfRows();
        assertThat(rows.get(0)).containsEntry("rpe", 3).containsEntry("source_id", "9001");
        assertThat(rows.get(1)).containsEntry("rpe", 6).containsEntry("source_id", "9002");
        for (var r : rows) {
            assertThat(r.get("log_date")).isEqualTo(r.get("calendar_date"));   // the activity's local date
            assertThat(r.get("created_by")).isNull();
            assertThat(r.get("on_behalf")).isEqualTo(false);
            assertThat(r.get("planned_session_id")).isNull();
        }
        assertThat(rows.get(1).get("log_date").toString()).isEqualTo("2026-11-11");   // local midnight crossed
        assertThat(current(a)).extracting(CheckinView::source).containsOnly("GARMIN_SEED");
        assertThat(current(a)).extracting(CheckinView::scope).containsOnly("activity");
    }

    @Test
    void importingTheSameExportAgainAddsNothingAndTheCommandAddsNothingEither() throws Exception {
        long a = athlete("Seed twice");
        Path zip = export("twice", act(9101, BASE_MS, BASE_MS + 2 * 3600_000, ",\"workoutRpe\":40,\"workoutFeel\":75"));
        importExport(a, zip);
        importExport(a, zip);
        assertThat(count("SELECT count(*) FROM activity_effort e JOIN activity x ON x.id = e.activity_id WHERE x.athlete_id = ?", a)).isEqualTo(1);
        assertThat(seedRows(a)).isEqualTo(1);

        var again = seed.seed(a);
        assertThat(again.seeded()).isZero();
        assertThat(again.alreadyChecked()).isEqualTo(1);
        assertThat(seedRows(a)).isEqualTo(1);
    }

    @Test
    void anActivityAlreadyStoredWithoutEffortGetsItFromAReuploadedExport() throws Exception {
        // The coach's case: 143 activities are in the database from earlier imports, without the effort fields.
        long a = athlete("Seed backfill");
        importExport(a, export("plain", act(9201, BASE_MS, BASE_MS + 2 * 3600_000, "")));
        assertThat(count("SELECT count(*) FROM activity WHERE athlete_id = ?", a)).isEqualTo(1);
        assertThat(seedRows(a)).isZero();

        importExport(a, export("with-effort", act(9201, BASE_MS, BASE_MS + 2 * 3600_000, ",\"workoutRpe\":20,\"workoutFeel\":75")));
        assertThat(count("SELECT count(*) FROM activity WHERE athlete_id = ?", a)).isEqualTo(1);   // no second activity
        assertThat(seedRows(a)).isEqualTo(1);
        assertThat(current(a).get(0).rpe()).isEqualTo(2);
    }

    @Test
    void aSummaryThatMatchesAnActivityFromAnotherFileWithinTheToleranceStillFillsItsEffort() throws Exception {
        long a = athlete("Seed tolerance");
        importExport(a, export("first", act(9301, BASE_MS, BASE_MS + 2 * 3600_000, "")));
        // Same workout under another id, 3 s off (as a FIT file would be): treated as the same activity.
        importExport(a, export("second", act(9399, BASE_MS + 3000, BASE_MS + 3000 + 2 * 3600_000, ",\"workoutRpe\":50")));
        assertThat(count("SELECT count(*) FROM activity WHERE athlete_id = ?", a)).isEqualTo(1);
        assertThat(seedRows(a)).isEqualTo(1);
    }

    @Test
    void anotherAthletesActivitiesAreNeverSeeded() throws Exception {
        long a = athlete("Seed own");
        long b = athlete("Seed other");
        importExport(a, export("own", act(9401, BASE_MS, BASE_MS + 2 * 3600_000, ",\"workoutRpe\":30")));
        assertThat(seed.seed(b).activitiesWithEffort()).isZero();
        assertThat(seedRows(b)).isZero();
    }

    // ---------------------------------------------------------------- manual beats seed

    private long activityWithEffort(long athlete, String sourceId, LocalDate date, Integer rpe) {
        long id = jdbc.sql("""
                INSERT INTO activity (athlete_id, source, source_id, sport, start_at, calendar_date, duration_s)
                VALUES (?, 'GARMIN', ?, 'running', ?::timestamptz, ?, 3600) RETURNING id""")
                .params(athlete, sourceId, date + "T07:00:00Z", date).query(Long.class).single();
        jdbc.sql("INSERT INTO activity_effort (activity_id, workout_rpe) VALUES (?, ?)").params(id, rpe).update();
        return id;
    }

    @Test
    void aManualCheckinMadeBeforeTheSeedIsNeverReplacedByIt() {
        long a = athlete("Manual first");
        LocalDate day = LocalDate.of(2026, 11, 10);
        long act = activityWithEffort(a, "m1", day, 30);
        manualCheckin(a, act, day, 8);

        var out = seed.seed(a);   // runs after the manual answer, so a plain "latest wins" would let the seed win
        assertThat(out.seeded()).isZero();
        assertThat(out.alreadyChecked()).isEqualTo(1);
        assertThat(seedRows(a)).isZero();
        assertThat(current(a)).singleElement().satisfies(c -> {
            assertThat(c.rpe()).isEqualTo(8);
            assertThat(c.source()).isEqualTo("MANUAL");
        });
    }

    @Test
    void aManualCheckinMadeAfterTheSeedWinsAndTheSeedStaysInTheHistory() {
        long a = athlete("Manual later");
        LocalDate day = LocalDate.of(2026, 11, 10);
        long act = activityWithEffort(a, "m2", day, 30);
        assertThat(seed.seed(a).seeded()).isEqualTo(1);
        assertThat(current(a)).singleElement().satisfies(c -> assertThat(c.source()).isEqualTo("GARMIN_SEED"));

        manualCheckin(a, act, day, 7);
        assertThat(current(a)).singleElement().satisfies(c -> {
            assertThat(c.rpe()).isEqualTo(7);
            assertThat(c.source()).isEqualTo("MANUAL");
        });
        assertThat(count("SELECT count(*) FROM subjective_log WHERE athlete_id = ?", a)).isEqualTo(2);   // append-only: the seed row is kept
        assertThat(seed.seed(a).seeded()).isZero();   // and running the seed again changes nothing
        assertThat(current(a)).singleElement().satisfies(c -> assertThat(c.rpe()).isEqualTo(7));
    }

    @Test
    void aWholeDayCheckinAndTheSeedOfItsActivityAreSeparateScopes() {
        long a = athlete("Day and activity");
        LocalDate day = LocalDate.of(2026, 11, 10);
        activityWithEffort(a, "m3", day, 40);
        checkins.insert(a, day, new CheckinRequest(day, null, null, 5, null, null, null, false, false, false, null), null, false);
        seed.seed(a);
        assertThat(current(a)).extracting(CheckinView::scope).containsExactlyInAnyOrder("day", "activity");
    }
}
