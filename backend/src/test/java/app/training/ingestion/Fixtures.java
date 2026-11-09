package app.training.ingestion;

import com.garmin.fit.DateTime;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.Manufacturer;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.Sport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synthetic export files shaped like the documented Whoop and Garmin exports
 * (ADR 0004). No personal data; deterministic values.
 */
public final class Fixtures {
    private Fixtures() {}

    public static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Whoop CSVs for {@code days} consecutive cycles starting at {@code first}. */
    public static void whoopCsvs(Path dir, LocalDate first, int days) throws IOException {
        var cycles = new StringBuilder("Cycle start time,Cycle end time,Cycle timezone,Recovery score %,"
                + "Resting heart rate (bpm),Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,"
                + "Energy burned (cal),Max HR (bpm),Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,"
                + "Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),"
                + "Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),"
                + "Sleep efficiency %,Sleep consistency %\n");
        var sleeps = new StringBuilder("Cycle start time,Cycle end time,Cycle timezone,Sleep onset,Wake onset,"
                + "Sleep performance %,Respiratory rate (rpm),Asleep duration (min),In bed duration (min),"
                + "Light sleep duration (min),Deep (SWS) duration (min),REM duration (min),Awake duration (min),"
                + "Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %,Nap\n");
        var workouts = new StringBuilder("Cycle start time,Cycle end time,Cycle timezone,Workout start time,"
                + "Workout end time,Duration (min),Activity name,Activity Strain,Energy burned (cal),Max HR (bpm),"
                + "Average HR (bpm),HR Zone 1 %,GPS enabled,Distance (meters),Altitude gain (meters)\n");
        var journal = new StringBuilder("Cycle start time,Cycle end time,Cycle timezone,Question text,Answered yes,Notes\n");
        for (int i = 0; i < days; i++) {
            LocalDate d = first.plusDays(i);
            LocalDateTime wake = d.atTime(7, 0);
            LocalDateTime nextWake = d.plusDays(1).atTime(7, 0);
            LocalDateTime onset = d.minusDays(1).atTime(23, 0);
            String cs = LOCAL.format(wake);
            String ce = LOCAL.format(nextWake);
            cycles.append(String.join(",", cs, ce, "UTC+02:00", String.valueOf(40 + i % 50), "48", "65.5", "33.4",
                    "96.1", "12.3", "2500", "170", "70", LOCAL.format(onset), cs, "88", "14.8", "420", "470", "200",
                    "100", "120", "50", "480", "30", "90", "80")).append('\n');
            sleeps.append(String.join(",", cs, ce, "UTC+02:00", LOCAL.format(onset), cs, "88", "14.8", "420", "470",
                    "200", "100", "120", "50", "480", "30", "90", "80", "false")).append('\n');
            workouts.append(String.join(",", cs, ce, "UTC+02:00", LOCAL.format(d.atTime(18, 0)),
                    LOCAL.format(d.atTime(19, 0)), "60", "Running", "11.2", "700", "175", "150", "10", "true",
                    "10000", "80")).append('\n');
            journal.append(String.join(",", cs, ce, "UTC+02:00", "Drank alcohol?", "false", "")).append('\n');
        }
        Files.writeString(dir.resolve("physiological_cycles.csv"), cycles);
        Files.writeString(dir.resolve("sleeps.csv"), sleeps);
        Files.writeString(dir.resolve("workouts.csv"), workouts);
        Files.writeString(dir.resolve("journal_entries.csv"), journal);
    }

    /** Start of the synthetic run on {@code d}: 18:00 local, as in the Whoop workouts. */
    public static Instant runStart(LocalDate d) {
        return d.atTime(18, 0).atZone(ZONE).toInstant();
    }

    /** A FIT activity file: a run of {@code seconds} with 1 Hz records. */
    public static byte[] fitRun(Instant start, int seconds, long serial) throws IOException {
        Path tmp = Files.createTempFile("fixture", ".fit");
        try {
            var enc = new FileEncoder(tmp.toFile(), Fit.ProtocolVersion.V2_0);
            var fileId = new FileIdMesg();
            fileId.setType(com.garmin.fit.File.ACTIVITY);
            fileId.setManufacturer(Manufacturer.GARMIN);
            fileId.setProduct(1);
            fileId.setSerialNumber(serial);
            fileId.setTimeCreated(new DateTime(Date.from(start)));
            enc.write(fileId);
            for (int s = 0; s < seconds; s++) {
                var r = new RecordMesg();
                r.setTimestamp(new DateTime(Date.from(start.plusSeconds(s))));
                r.setHeartRate((short) (130 + s % 30));
                r.setSpeed(3.0f);
                r.setCadence((short) 88);
                r.setDistance(3.0f * s);
                r.setAltitude(80f);
                enc.write(r);
            }
            var session = new SessionMesg();
            session.setTimestamp(new DateTime(Date.from(start.plusSeconds(seconds))));
            session.setStartTime(new DateTime(Date.from(start)));
            session.setSport(Sport.RUNNING);
            session.setTotalTimerTime((float) seconds);
            session.setTotalElapsedTime((float) seconds);
            session.setTotalDistance(3.0f * seconds);
            session.setAvgHeartRate((short) 145);
            session.setMaxHeartRate((short) 159);
            session.setTotalAscent(12);
            enc.write(session);
            enc.close();
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A Garmin account-export ZIP for {@code days} days: daily summaries, HRV
     * health status, sleep, summarized activities, and FIT files nested in an
     * UploadedFiles zip (one run per day, matching the summaries).
     */
    public static Path garminExport(Path dir, LocalDate first, int days) throws IOException {
        var uds = new StringBuilder("[");
        var health = new StringBuilder("[");
        var sleep = new StringBuilder("[");
        var acts = new StringBuilder("[{\"summarizedActivitiesExport\":[");
        var nested = new ByteArrayOutputStream();
        try (var nz = new ZipOutputStream(nested)) {
            for (int i = 0; i < days; i++) {
                LocalDate d = first.plusDays(i);
                String sep = i == 0 ? "" : ",";
                uds.append(sep).append("""
                        {"calendarDate":"%s","restingHeartRate":48,"currentDayRestingHeartRate":%d,
                         "allDayStress":{"aggregatorList":[{"type":"TOTAL","averageStressLevel":28},{"type":"AWAKE","averageStressLevel":35}]},
                         "bodyBattery":{"bodyBatteryStatList":[{"bodyBatteryStatType":"HIGHEST","statsValue":90},{"bodyBatteryStatType":"LOWEST","statsValue":20}]},
                         "respiration":{"avgSleepRespirationValue":14.5},"averageSpo2Value":95.0}""".formatted(d, 47 + i % 3));
                health.append(sep).append("""
                        {"calendarDate":"%s","metrics":[{"type":"HRV","value":%s,"status":"IN_RANGE"},{"type":"HR","value":47},
                         {"type":"SKIN_TEMP_C","value":-1.1,"status":"IN_RANGE"}]}"""
                        .formatted(d, 60.0 + i % 5));
                // One day with a placeholder HRV reading, as real exports contain.
                if (i == 1) health.append(",").append("""
                        {"calendarDate":"%s","metrics":[{"type":"HRV","value":-1.0,"status":"UNKNOWN"}]}"""
                        .formatted(d.minusYears(1)));
                Instant sleepStart = d.minusDays(1).atTime(23, 0).atZone(ZONE).toInstant();
                Instant sleepEnd = d.atTime(7, 0).atZone(ZONE).toInstant();
                sleep.append(sep).append("""
                        {"calendarDate":"%s","sleepStartTimestampGMT":"%s","sleepEndTimestampGMT":"%s",
                         "deepSleepSeconds":6000,"lightSleepSeconds":12000,"remSleepSeconds":7200,"awakeSleepSeconds":3600,
                         "sleepScores":{"overallScore":81},"averageRespiration":12.5,"avgSleepStress":17.25,
                         "spo2SleepSummary":{"averageSPO2":96.2,"lowestSPO2":86.0},"retro":false}""".formatted(d, gmt(sleepStart), gmt(sleepEnd)));
                Instant run = runStart(d);
                acts.append(sep).append("""
                        {"activityId":%d,"name":"Easy run","activityType":"running","sportType":"RUNNING",
                         "startTimeGmt":%d.0,"startTimeLocal":%d.0,"duration":3600000.0,"distance":1000000.0,"elevationGain":8000.0,
                         "avgHr":145,"maxHr":160,"calories":700,"activityTrainingLoad":95.4,
                         "aerobicTrainingEffect":3.1,"anaerobicTrainingEffect":0.4}"""
                        .formatted(1_000_000L + i, run.toEpochMilli(), localMillis(run)));
                // FIT start is 3 s off the summary, as real devices often are.
                nz.putNextEntry(new ZipEntry("athlete_" + (1_000_000L + i) + ".fit"));
                nz.write(fitRun(run.plusSeconds(3), 120, 4242));
                nz.closeEntry();
                // Bulk exports are mostly all-day monitoring files; they must be skipped.
                nz.putNextEntry(new ZipEntry("athlete_monitor_" + i + ".fit"));
                nz.write(fitMonitoring(run.minusSeconds(3600), 9000 + i));
                nz.closeEntry();
            }
        }
        uds.append("]");
        health.append("]");
        sleep.append("]");
        acts.append("]}]");

        Path zip = dir.resolve("garmin-export.zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(z, "DI_CONNECT/DI-Connect-Aggregator/UDSFile_2024-01-01_2024-04-10.json", uds.toString());
            put(z, "DI_CONNECT/DI-Connect-Wellness/2024-01-01_2024-04-10_123_healthStatusData.json", health.toString());
            put(z, "DI_CONNECT/DI-Connect-Wellness/2024-01-01_2024-04-10_123_sleepData.json", sleep.toString());
            put(z, "DI_CONNECT/DI-Connect-Fitness/athlete_0_summarizedActivities.json", acts.toString());
            z.putNextEntry(new ZipEntry("DI_CONNECT/DI-Connect-Uploaded-Files/UploadedFiles_0-_Part1.zip"));
            z.write(nested.toByteArray());
            z.closeEntry();
            put(z, "DI_CONNECT/DI-Connect-User/user_profile.json", "{\"note\":\"ignored\"}");
        }
        return zip;
    }

    /** Garmin's startTimeLocal: local wall-clock time encoded as if it were UTC epoch ms. */
    public static long localMillis(Instant i) {
        return LocalDateTime.ofInstant(i, ZONE).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }

    /** A non-activity (monitoring) FIT file, like the thousands in a bulk export. */
    public static byte[] fitMonitoring(Instant created, long serial) throws IOException {
        Path tmp = Files.createTempFile("monitor", ".fit");
        try {
            var enc = new FileEncoder(tmp.toFile(), Fit.ProtocolVersion.V2_0);
            var fileId = new FileIdMesg();
            fileId.setType(com.garmin.fit.File.MONITORING_B);
            fileId.setManufacturer(Manufacturer.GARMIN);
            fileId.setProduct(1);
            fileId.setSerialNumber(serial);
            fileId.setTimeCreated(new DateTime(Date.from(created)));
            enc.write(fileId);
            for (int m = 0; m < 30; m++) {
                var mon = new com.garmin.fit.MonitoringMesg();
                mon.setTimestamp(new DateTime(Date.from(created.plusSeconds(60L * m))));
                mon.setHeartRate((short) 60);
                enc.write(mon);
            }
            enc.close();
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String gmt(Instant i) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.S").format(LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC));
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
