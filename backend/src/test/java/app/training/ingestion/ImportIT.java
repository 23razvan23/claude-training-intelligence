package app.training.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class ImportIT extends IntegrationTest {
    static final LocalDate FIRST = LocalDate.of(2024, 3, 1);

    @Autowired ImportRunner runner;
    @Autowired ImportBatchRepository batches;
    @Autowired CoverageService coverage;

    @TempDir Path dir;

    private ImportBatch run(long athleteId, ImportKind kind, List<Path> files) throws Exception {
        // The runner deletes its inputs, so hand it copies.
        var copies = new java.util.ArrayList<Path>();
        for (Path f : files) {
            Path c = Files.createTempFile(dir, "upload", f.getFileName().toString());
            Files.copy(f, c, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            copies.add(c);
        }
        long id = batches.create(athleteId, kind, "test", null, createCoach());
        runner.run(id, athleteId, kind, copies, null);
        return batches.find(id).orElseThrow();
    }

    private long createCoach() {
        return jdbc.sql("""
                INSERT INTO app_user (username, password_hash, role) VALUES ('c' || nextval('app_user_id_seq'), 'x', 'COACH')
                RETURNING id""").query(Long.class).single();
    }

    private Map<String, Long> rawCounts(long athleteId) {
        return Map.of(
                "whoop_reference", count("SELECT count(*) FROM whoop_reference WHERE athlete_id = ?", athleteId),
                "sleep_session", count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", athleteId),
                "activity", count("SELECT count(*) FROM activity WHERE athlete_id = ?", athleteId),
                "daily_wellness", count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ?", athleteId),
                "activity_stream", count("SELECT count(*) FROM activity_stream s JOIN activity a ON a.id = s.activity_id WHERE a.athlete_id = ?", athleteId),
                "whoop_journal_entry", count("SELECT count(*) FROM whoop_journal_entry WHERE athlete_id = ?", athleteId));
    }

    @Test
    void whoopImportIsCompleteAndIdempotent() throws Exception {
        long athlete = newAthlete("Whoop athlete");
        Fixtures.whoopCsvs(dir, FIRST, 10);
        var files = List.of(dir.resolve("physiological_cycles.csv"), dir.resolve("sleeps.csv"),
                dir.resolve("workouts.csv"), dir.resolve("journal_entries.csv"));

        var first = run(athlete, ImportKind.WHOOP_EXPORT, files);
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        var afterFirst = rawCounts(athlete);
        assertThat(afterFirst).containsEntry("whoop_reference", 10L).containsEntry("sleep_session", 10L)
                .containsEntry("activity", 10L).containsEntry("whoop_journal_entry", 10L);

        var second = run(athlete, ImportKind.WHOOP_EXPORT, files);
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(rawCounts(athlete)).isEqualTo(afterFirst);
        assertThat(second.stats().path("whoop_reference").path("duplicates").asLong()).isEqualTo(10);
        assertThat(second.stats().path("whoop_reference").path("inserted").asLong()).isZero();

        // Values and cycle dates: a cycle starting 07:00 UTC+02:00 belongs to that local date.
        var row = jdbc.sql("SELECT cycle_date, recovery_pct, day_strain, hrv_rmssd_ms FROM whoop_reference WHERE athlete_id = ? ORDER BY cycle_start LIMIT 1")
                .param(athlete).query().singleRow();
        assertThat(row.get("cycle_date").toString()).isEqualTo("2024-03-01");
        assertThat(row.get("recovery_pct")).isEqualTo(40);
        assertThat(row.get("day_strain").toString()).isEqualTo("12.3");
        assertThat(row.get("hrv_rmssd_ms").toString()).isEqualTo("65.5");
        assertThat(count("SELECT count(*) FROM activity WHERE athlete_id = ? AND sport = 'running' AND whoop_strain = 11.2", athlete)).isEqualTo(10);
    }

    @Test
    void whoopExportZipIsAccepted() throws Exception {
        long athlete = newAthlete("Zip athlete");
        Fixtures.whoopCsvs(dir, FIRST, 3);
        Path zip = dir.resolve("my_whoop_data.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            for (String n : List.of("physiological_cycles.csv", "sleeps.csv", "workouts.csv", "journal_entries.csv")) {
                z.putNextEntry(new java.util.zip.ZipEntry("my_whoop_data_2024/" + n));
                z.write(Files.readAllBytes(dir.resolve(n)));
                z.closeEntry();
            }
        }
        assertThat(run(athlete, ImportKind.WHOOP_EXPORT, List.of(zip)).status()).isEqualTo("SUCCEEDED");
        assertThat(rawCounts(athlete)).containsEntry("whoop_reference", 3L).containsEntry("activity", 3L);
    }

    @Test
    void unrecognisedCsvIsReportedNotFatal() throws Exception {
        long athlete = newAthlete("Odd athlete");
        Path odd = dir.resolve("odd.csv");
        Files.writeString(odd, "foo,bar\n1,2\n");
        var batch = run(athlete, ImportKind.WHOOP_EXPORT, List.of(odd));
        assertThat(batch.status()).isEqualTo("SUCCEEDED");
        assertThat(batch.warnings().toString()).contains("not a recognised Whoop CSV").contains("No physiological_cycles.csv");
    }

    @Test
    void garminExportIsCompleteAndIdempotent() throws Exception {
        long athlete = newAthlete("Garmin athlete");
        Path zip = Fixtures.garminExport(dir, FIRST, 5);

        var first = run(athlete, ImportKind.GARMIN_EXPORT, List.of(zip));
        assertThat(first.status()).as(first.toString()).isEqualTo("SUCCEEDED");
        var afterFirst = rawCounts(athlete);
        // FIT files matched the summaries (3 s apart): 5 activities, not 10.
        assertThat(afterFirst).containsEntry("activity", 5L).containsEntry("sleep_session", 5L)
                .containsEntry("daily_wellness", 10L).containsEntry("activity_stream", 5L * 120);

        var second = run(athlete, ImportKind.GARMIN_EXPORT, List.of(zip));
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(rawCounts(athlete)).isEqualTo(afterFirst);
        assertThat(second.stats().path("fit_file").path("duplicates").asLong()).isEqualTo(5);
        // The 5 monitoring FIT files are skipped, never turned into activities.
        assertThat(first.stats().path("fit_file").path("skipped").asLong()).isEqualTo(5);
        assertThat(first.warnings()).isEmpty();
        assertThat(second.stats().path("fit_file").path("inserted").asLong()).isZero();

        // Unit conversions from the summarized-activities JSON (ms, cm).
        var act = jdbc.sql("SELECT duration_s, distance_m, elevation_gain_m, training_load, sport FROM activity WHERE athlete_id = ? ORDER BY start_at LIMIT 1")
                .param(athlete).query().singleRow();
        assertThat(act.get("duration_s")).isEqualTo(3600);
        assertThat(act.get("distance_m").toString()).isEqualTo("10000.0");
        assertThat(act.get("elevation_gain_m").toString()).isEqualTo("80.0");
        assertThat(act.get("training_load").toString()).isEqualTo("95.4");
        assertThat(act.get("sport")).isEqualTo("running");

        // Wellness from two files merges into one row per day.
        var w = jdbc.sql("SELECT resting_hr, hrv_rmssd_ms, stress_avg, body_battery_high, body_battery_low, respiration_rate FROM daily_wellness_merged WHERE athlete_id = ? AND calendar_date = ?")
                .params(athlete, FIRST).query().singleRow();
        // currentDayRestingHeartRate (47) wins over the smoothed restingHeartRate (48).
        assertThat(w.get("resting_hr")).isEqualTo(47);
        assertThat(w.get("hrv_rmssd_ms").toString()).isEqualTo("60.0");
        assertThat(w.get("stress_avg")).isEqualTo(28);
        assertThat(w.get("body_battery_high")).isEqualTo(90);
        assertThat(w.get("body_battery_low")).isEqualTo(20);

        // Overnight metrics from sleepData.json.
        var sl = jdbc.sql("SELECT avg_respiration, avg_sleep_stress, avg_spo2_pct, lowest_spo2_pct FROM sleep_session WHERE athlete_id = ? AND cycle_date = ?")
                .params(athlete, FIRST).query().singleRow();
        assertThat(sl.get("avg_respiration").toString()).isEqualTo("12.5");
        assertThat(sl.get("avg_sleep_stress").toString()).isEqualTo("17.25");
        assertThat(sl.get("avg_spo2_pct").toString()).isEqualTo("96.2");
        assertThat(sl.get("lowest_spo2_pct").toString()).isEqualTo("86.0");

        // Sleep ending 07:00 local opens that day's cycle; the evening run belongs to it.
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ? AND cycle_date = ?", athlete, FIRST)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM activity_with_cycle WHERE athlete_id = ? AND cycle_date = ? AND NOT cycle_from_calendar", athlete, FIRST)).isEqualTo(1);
    }

    @Test
    void activityLocalDateComesFromStartTimeLocal() throws Exception {
        // Athlete profile says Bucharest, but the run was logged in New York at 22:30 local
        // (03:30 UTC next day): the activity belongs to the local date.
        long athlete = newAthlete("Traveller");
        java.time.Instant start = java.time.Instant.parse("2024-03-02T03:30:00Z");
        long local = java.time.LocalDateTime.of(2024, 3, 1, 22, 30).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        Path zip = dir.resolve("travel.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("DI_CONNECT/DI-Connect-Fitness/u_summarizedActivities.json"));
            z.write(("[{\"summarizedActivitiesExport\":[{\"activityId\":42,\"activityType\":\"running\",\"startTimeGmt\":"
                    + start.toEpochMilli() + ".0,\"startTimeLocal\":" + local + ".0,\"duration\":1800000.0}]}]").getBytes());
            z.closeEntry();
        }
        assertThat(run(athlete, ImportKind.GARMIN_EXPORT, List.of(zip)).status()).isEqualTo("SUCCEEDED");
        var d = jdbc.sql("SELECT calendar_date FROM activity WHERE athlete_id = ?").param(athlete).query(LocalDate.class).single();
        assertThat(d).isEqualTo(LocalDate.of(2024, 3, 1));
    }

    @Test
    void whoopSleepRespirationIsStored() throws Exception {
        long athlete = newAthlete("Whoop sleeper");
        Fixtures.whoopCsvs(dir, FIRST, 2);
        run(athlete, ImportKind.WHOOP_EXPORT, List.of(dir.resolve("sleeps.csv")));
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ? AND avg_respiration = 14.8", athlete)).isEqualTo(2);
    }

    @Test
    void looseFitUploadAttachesToExistingActivityOrCreatesOne() throws Exception {
        long athlete = newAthlete("FIT athlete");
        run(athlete, ImportKind.GARMIN_EXPORT, List.of(Fixtures.garminExport(dir, FIRST, 1)));
        assertThat(rawCounts(athlete)).containsEntry("activity", 1L);

        Path sameRun = dir.resolve("same.fit");
        Files.write(sameRun, Fixtures.fitRun(Fixtures.runStart(FIRST).plusSeconds(3), 120, 4242));
        Path newRun = dir.resolve("new.fit");
        Files.write(newRun, Fixtures.fitRun(Fixtures.runStart(FIRST.plusDays(1)), 60, 4242));
        Path junk = dir.resolve("junk.fit");
        Files.writeString(junk, "not a fit file");

        var batch = run(athlete, ImportKind.FIT_UPLOAD, List.of(sameRun, newRun, junk));
        assertThat(batch.status()).isEqualTo("SUCCEEDED");
        assertThat(rawCounts(athlete)).containsEntry("activity", 2L).containsEntry("activity_stream", 180L);
        assertThat(batch.warnings().toString()).contains("junk");
    }

    @Test
    void coverageReportCountsPairedDaysAndGaps() throws Exception {
        long athlete = newAthlete("Coverage athlete");
        // Whoop: Mar 1-10. Garmin: Mar 4-13. Paired: Mar 4-10 = 7 days.
        Fixtures.whoopCsvs(dir, FIRST, 10);
        run(athlete, ImportKind.WHOOP_EXPORT, List.of(dir.resolve("physiological_cycles.csv")));
        run(athlete, ImportKind.GARMIN_EXPORT, List.of(Fixtures.garminExport(dir, FIRST.plusDays(3), 10)));

        var r = coverage.report(athlete, null, null);
        assertThat(r.from()).isEqualTo(FIRST.minusDays(0));
        assertThat(r.to()).isEqualTo(FIRST.plusDays(12));
        assertThat(r.totalDays()).isEqualTo(13);
        assertThat(r.whoopCompleteDays()).isEqualTo(10);
        assertThat(r.garminCompleteDays()).isEqualTo(10);
        assertThat(r.pairedDays()).isEqualTo(7);
        assertThat(r.pairedGaps()).extracting(CoverageService.Gap::days).containsExactly(3L, 3L);
        assertThat(r.fieldDays()).containsEntry("garminActivityDays", 10L);
    }
}
