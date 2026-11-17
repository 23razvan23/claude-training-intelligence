package app.training.garmin;

import app.training.ingestion.Fixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What the sidecar returns for synthetic days: Garmin Connect's live response shapes
 * (garminconnect 0.3.20's typed models), carrying the same values as
 * {@link Fixtures#garminExport} gives for the same day index, so the two paths can be compared.
 * Entirely synthetic.
 */
final class GarminApiFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private GarminApiFixtures() {}

    /** Day {@code i} counts from {@code first}, like the export fixture; {@code tweak} may change a day before it is added. */
    static ObjectNode payload(LocalDate first, int fromIndex, int count, String tokens, Consumer<ObjectNode> tweak) {
        ObjectNode out = JSON.createObjectNode().put("tokens", tokens);
        var days = out.putArray("days");
        var acts = out.putArray("activities");
        out.putArray("missing");
        for (int i = fromIndex; i < fromIndex + count; i++) {
            LocalDate d = first.plusDays(i);
            ObjectNode day = days.addObject().put("date", d.toString());
            day.putObject("summary")
                    .put("calendarDate", d.toString())
                    .put("restingHeartRate", 47 + i % 3)
                    .put("lastSevenDaysAvgRestingHeartRate", 48)
                    .put("averageStressLevel", 28)
                    .put("bodyBatteryHighestValue", 90)
                    .put("bodyBatteryLowestValue", 20)
                    .put("averageSpo2", 95.0)
                    .put("avgWakingRespirationValue", 14.5)
                    .put("totalSteps", 9000);
            Instant sleepStart = d.minusDays(1).atTime(23, 0).atZone(Fixtures.ZONE).toInstant();
            Instant sleepEnd = d.atTime(7, 0).atZone(Fixtures.ZONE).toInstant();
            ObjectNode sleep = day.putObject("sleep")
                    .put("calendarDate", d.toString())
                    .put("sleepStartTimestampGMT", sleepStart.toEpochMilli())
                    .put("sleepEndTimestampGMT", sleepEnd.toEpochMilli())
                    .put("deepSleepSeconds", 6000).put("lightSleepSeconds", 12000)
                    .put("remSleepSeconds", 7200).put("awakeSleepSeconds", 3600)
                    .put("averageRespirationValue", 12.5).put("avgSleepStress", 17.25)
                    .put("averageSpO2Value", 96.2).put("lowestSpO2Value", 86.0);
            sleep.putObject("sleepScores").putObject("overall").put("value", 81);
            day.putObject("hrv").put("calendarDate", d.toString()).put("lastNightAvg", 60.0 + i % 5).put("status", "BALANCED");

            Instant run = Fixtures.runStart(d);
            long id = 1_000_000L + i;
            ObjectNode summary = JSON.createObjectNode()
                    .put("activityId", id)
                    .put("activityName", "Easy run")
                    .put("startTimeGMT", WALL.format(LocalDateTime.ofInstant(run, ZoneOffset.UTC)))
                    .put("startTimeLocal", WALL.format(LocalDateTime.ofInstant(run, Fixtures.ZONE)))
                    .put("duration", 3600.0).put("distance", 10000.0).put("elevationGain", 80.0)
                    .put("averageHR", 145.0).put("maxHR", 160.0).put("calories", 700.0)
                    .put("activityTrainingLoad", 95.4).put("aerobicTrainingEffect", 3.1).put("anaerobicTrainingEffect", 0.4);
            summary.putObject("activityType").put("typeKey", "running").put("typeId", 1);
            ObjectNode act = acts.addObject();
            act.set("summary", summary);
            try {
                act.put("fit", zip(id + "_ACTIVITY.fit", Fixtures.fitRun(run.plusSeconds(3), 120, 4242)));
                day.put("wellnessFit", zip(d + "_" + (9000 + i) + "_WELLNESS.fit",
                        Fixtures.fitMonitoring(run.minusSeconds(3600), 9000 + i, Fixtures.MONITORING_SAMPLES, Fixtures.MONITORING_HR)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (tweak != null) tweak.accept(day);
        }
        return out;
    }

    static ObjectNode payload(LocalDate first, int fromIndex, int count, String tokens) {
        return payload(first, fromIndex, count, tokens, null);
    }

    /** Base64 of a ZIP holding one file, as Garmin serves its downloads. */
    static String zip(String name, byte[] content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(content);
            z.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
