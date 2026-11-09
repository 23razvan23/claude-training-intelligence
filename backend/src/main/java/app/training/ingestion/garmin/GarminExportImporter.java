package app.training.ingestion.garmin;

import static app.training.ingestion.garmin.GarminFields.*;

import app.training.ingestion.RawRows;
import app.training.ingestion.RawWriter;
import app.training.ingestion.SportNames;
import app.training.ingestion.TimeParsing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Imports a Garmin Connect account data export (the ZIP from "Export your data"),
 * and loose FIT files. Wellness and sleep JSON are read first, then activity
 * summaries, then FIT files, which attach per-second streams to the matching
 * summary (same start within the configured tolerance) or create the activity.
 */
@Component
public class GarminExportImporter {
    private final RawWriter writer;
    private final ObjectMapper json;
    private final Duration matchTolerance;

    public GarminExportImporter(
            RawWriter writer,
            ObjectMapper json,
            @Value("${app.import.activity-match-tolerance-seconds:120}") long toleranceSeconds) {
        this.writer = writer;
        this.json = json;
        this.matchTolerance = Duration.ofSeconds(toleranceSeconds);
    }

    public void importExport(RawWriter.Ctx ctx, Path zip, ZoneId zone) throws IOException {
        try (var zf = new ZipFile(zip.toFile())) {
            var entries = Collections.list(zf.entries()).stream().filter(e -> !e.isDirectory()).toList();
            int recognised = 0;
            for (ZipEntry e : entries) {
                String n = e.getName();
                if (SLEEP.matcher(n).matches()) {
                    recognised++;
                    eachObject(ctx, zf, e, o -> sleep(ctx, o, zone));
                } else if (DAILY_SUMMARY.matcher(n).matches()) {
                    recognised++;
                    eachObject(ctx, zf, e, o -> dailySummary(ctx, o));
                } else if (HEALTH_STATUS.matcher(n).matches()) {
                    recognised++;
                    eachObject(ctx, zf, e, o -> healthStatus(ctx, o));
                }
            }
            for (ZipEntry e : entries) {
                if (SUMMARIZED_ACTIVITIES.matcher(e.getName()).matches()) {
                    recognised++;
                    eachObject(ctx, zf, e, o -> {
                        JsonNode list = o.get(ACTIVITIES_WRAPPER);
                        if (list != null && list.isArray()) list.forEach(a -> activitySummary(ctx, a, zone));
                        else if (o.has(ACT_ID)) activitySummary(ctx, o, zone);
                    });
                }
            }
            for (ZipEntry e : entries) {
                String n = e.getName();
                if (FIT.matcher(n).matches()) {
                    recognised++;
                    try (InputStream in = zf.getInputStream(e)) {
                        fit(ctx, n, in.readAllBytes(), zone);
                    }
                } else if (NESTED_ZIP.matcher(n).matches()) {
                    try (var nested = new ZipInputStream(zf.getInputStream(e))) {
                        ZipEntry ne;
                        while ((ne = nested.getNextEntry()) != null) {
                            if (!ne.isDirectory() && FIT.matcher(ne.getName()).matches()) {
                                recognised++;
                                fit(ctx, ne.getName(), nested.readAllBytes(), zone);
                            }
                        }
                    }
                }
            }
            if (recognised == 0) {
                ctx.stats().warn("No Garmin export files recognised in the ZIP. Is this the Garmin Connect account export?");
            }
        }
    }

    /** One FIT file, from a bulk export or a manual upload. Non-activity FIT files are skipped. */
    public void fit(RawWriter.Ctx ctx, String name, byte[] bytes, ZoneId zone) {
        FitActivityParser.Parsed parsed;
        try {
            parsed = FitActivityParser.parse(bytes);
        } catch (IllegalArgumentException e) {
            ctx.stats().skipped("fit_file", 1);
            ctx.stats().warn(name + ": " + e.getMessage());
            return;
        }
        if (!parsed.activity()) {
            // Monitoring, sleep, settings... files: most of a bulk export. Skipped quietly.
            ctx.stats().skipped("fit_file", 1);
            return;
        }
        if (parsed.sessions().size() > 1) {
            ctx.stats().warn(name + ": multisport file with " + parsed.sessions().size()
                    + " sessions; imported as one activity using the first session's sport");
        }
        var s = parsed.sessions().getFirst();
        Instant start = s.start() != null ? s.start()
                : parsed.stream().isEmpty() ? parsed.timeCreated() : parsed.stream().getFirst().ts();
        if (start == null) {
            ctx.stats().skipped("fit_file", 1);
            ctx.stats().warn(name + ": no start time");
            return;
        }
        long activityId = writer.activityNear(ctx.athleteId(), RawRows.GARMIN, start, matchTolerance)
                .map(id -> {
                    ctx.stats().duplicates("activity", 1);
                    return id;
                })
                .orElseGet(() -> writer.activity(ctx, new RawRows.Activity(
                        RawRows.GARMIN,
                        "fit:" + parsed.serialNumber() + ":" + start.getEpochSecond(),
                        SportNames.normalize(s.sport()),
                        s.subSport() == null ? s.sport() : s.sport() + "/" + s.subSport(),
                        start,
                        LocalDate.ofInstant(start, zone),
                        s.timerTimeS() == null ? null : Math.round(s.timerTimeS()),
                        dec(s.distanceM(), 1),
                        s.ascentM() == null ? null : BigDecimal.valueOf(s.ascentM()),
                        s.avgHr(),
                        s.maxHr(),
                        s.calories(),
                        null,
                        dec(s.aerobicTe(), 1),
                        dec(s.anaerobicTe(), 1),
                        null)));
        long streamRowsBefore = ctx.stats().inserted("activity_stream");
        writer.stream(ctx, activityId, parsed.stream());
        // A FIT file counts as new only if it added stream rows.
        if (ctx.stats().inserted("activity_stream") > streamRowsBefore || parsed.stream().isEmpty()) {
            ctx.stats().inserted("fit_file", 1);
        } else {
            ctx.stats().duplicates("fit_file", 1);
        }
    }

    // ---------------------------------------------------------------- JSON entries

    private void eachObject(RawWriter.Ctx ctx, ZipFile zf, ZipEntry e, Consumer<JsonNode> handler) throws IOException {
        JsonNode root;
        try (InputStream in = zf.getInputStream(e)) {
            root = json.readTree(in);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            ctx.stats().warn(e.getName() + ": not valid JSON, skipped");
            return;
        }
        List<JsonNode> objects = new ArrayList<>();
        if (root != null && root.isArray()) root.forEach(objects::add);
        else if (root != null && root.isObject()) objects.add(root);
        for (JsonNode o : objects) {
            try {
                handler.accept(o);
            } catch (RuntimeException ex) {
                ctx.stats().warn(e.getName() + ": skipped a record: " + ex.getMessage());
            }
        }
    }

    private void sleep(RawWriter.Ctx ctx, JsonNode o, ZoneId zone) {
        Instant start = gmt(text(o, SLEEP_START_GMT));
        Instant end = gmt(text(o, SLEEP_END_GMT));
        LocalDate date = date(o.get(SLEEP_DATE));
        if (start == null || end == null || !end.isAfter(start)) {
            ctx.stats().skipped("sleep_session", 1);
            return;
        }
        writer.sleep(ctx, new RawRows.Sleep(
                RawRows.GARMIN,
                date != null ? date.toString() : start.toString(),
                start,
                end,
                LocalDate.ofInstant(end, zone),
                false,
                integer(o, SLEEP_DEEP_S),
                integer(o, SLEEP_LIGHT_S),
                integer(o, SLEEP_REM_S),
                integer(o, SLEEP_AWAKE_S),
                firstInt(o, SLEEP_SCORE_PATHS),
                positiveDec(o, SLEEP_AVG_RESPIRATION, 1),
                positiveDec(o, SLEEP_AVG_STRESS, 2),
                positiveDec(o, SLEEP_AVG_SPO2, 1),
                positiveDec(o, SLEEP_LOWEST_SPO2, 1)));
    }

    private void dailySummary(RawWriter.Ctx ctx, JsonNode o) {
        LocalDate date = date(o.get(DAY_DATE));
        if (date == null) {
            ctx.stats().skipped("daily_wellness", 1);
            return;
        }
        Integer stress = null;
        for (JsonNode a : iter(at(o, DAY_STRESS_LIST))) {
            if ("TOTAL".equalsIgnoreCase(a.path("type").asText())) stress = intOrNull(a.get("averageStressLevel"));
        }
        Integer bbHigh = null;
        Integer bbLow = null;
        for (JsonNode b : iter(at(o, DAY_BODY_BATTERY_LIST))) {
            String type = b.path("bodyBatteryStatType").asText();
            if ("HIGHEST".equalsIgnoreCase(type)) bbHigh = intOrNull(b.get("statsValue"));
            if ("LOWEST".equalsIgnoreCase(type)) bbLow = intOrNull(b.get("statsValue"));
        }
        // Garmin uses negative values (-1, -2) for "no reading".
        if (stress != null && stress < 0) stress = null;
        writer.wellness(ctx, new RawRows.Wellness(
                RawRows.GARMIN, "uds", date,
                positive(firstInt(o, DAY_RESTING_HR)),
                null, null,
                firstDec(o, DAY_RESPIRATION),
                firstDec(o, DAY_SPO2),
                stress, bbHigh, bbLow));
    }

    private void healthStatus(RawWriter.Ctx ctx, JsonNode o) {
        LocalDate date = date(o.get(HEALTH_DATE));
        if (date == null) return;
        for (JsonNode m : iter(o.get(HEALTH_METRICS))) {
            // Garmin writes placeholders (0, -1) for "no reading"; real overnight RMSSD is > 0.
            if (HEALTH_HRV_TYPE.equalsIgnoreCase(m.path("type").asText()) && m.path("value").isNumber()
                    && m.get("value").asDouble() > 0) {
                writer.wellness(ctx, new RawRows.Wellness(
                        RawRows.GARMIN, "healthStatus", date, null,
                        BigDecimal.valueOf(m.get("value").asDouble()).setScale(1, RoundingMode.HALF_UP),
                        m.hasNonNull("status") ? m.get("status").asText() : null,
                        null, null, null, null, null));
            }
        }
    }

    private void activitySummary(RawWriter.Ctx ctx, JsonNode a, ZoneId zone) {
        if (!a.hasNonNull(ACT_ID) || !a.hasNonNull(ACT_START_GMT_MS)) {
            ctx.stats().skipped("activity", 1);
            return;
        }
        Instant start = TimeParsing.epochMillis(a.get(ACT_START_GMT_MS).asDouble());
        LocalDate localDate = a.hasNonNull(ACT_START_LOCAL_MS)
                ? LocalDate.ofInstant(TimeParsing.epochMillis(a.get(ACT_START_LOCAL_MS).asDouble()), ZoneOffset.UTC)
                : LocalDate.ofInstant(start, zone);
        String sourceId = a.get(ACT_ID).asText();
        String type = null;
        for (String k : ACT_TYPE) if (type == null && a.hasNonNull(k)) type = a.get(k).asText();

        Integer durationS = a.hasNonNull(ACT_DURATION)
                ? (int) Math.round(a.get(ACT_DURATION).asDouble() * ACT_DURATION_TO_SECONDS) : null;
        BigDecimal distanceM = a.hasNonNull(ACT_DISTANCE)
                ? BigDecimal.valueOf(a.get(ACT_DISTANCE).asDouble() * ACT_DISTANCE_TO_METRES).setScale(1, RoundingMode.HALF_UP) : null;
        String sport = SportNames.normalize(type);
        if ("running".equals(sport) && durationS != null && durationS > 0 && distanceM != null
                && distanceM.doubleValue() / durationS > 12) {
            ctx.stats().warn("Activity " + sourceId + ": implausible running speed; check the distance/duration units in GarminFields");
        }

        // Same workout already imported from a FIT file (or under another id)? Keep the first.
        if (writer.activityNear(ctx.athleteId(), RawRows.GARMIN, start, matchTolerance).isPresent()) {
            ctx.stats().duplicates("activity", 1);
            return;
        }
        writer.activity(ctx, new RawRows.Activity(
                RawRows.GARMIN,
                sourceId,
                sport,
                type,
                start,
                localDate,
                durationS,
                distanceM,
                a.hasNonNull(ACT_ELEVATION_GAIN)
                        ? BigDecimal.valueOf(a.get(ACT_ELEVATION_GAIN).asDouble() * ACT_ELEVATION_TO_METRES).setScale(1, RoundingMode.HALF_UP)
                        : null,
                integer(a, ACT_AVG_HR),
                integer(a, ACT_MAX_HR),
                integer(a, ACT_CALORIES),
                decimal(a, ACT_TRAINING_LOAD),
                decimal(a, ACT_AEROBIC_TE),
                decimal(a, ACT_ANAEROBIC_TE),
                null));
    }

    // ---------------------------------------------------------------- JSON helpers

    /** Navigates a slash-separated path; returns a missing node when absent. */
    static JsonNode at(JsonNode o, String path) {
        JsonNode cur = o;
        for (String p : path.split("/")) {
            if (cur == null) return null;
            cur = cur.get(p);
        }
        return cur;
    }

    private static Iterable<JsonNode> iter(JsonNode n) {
        return n != null && n.isArray() ? n : List.of();
    }

    private static String text(JsonNode o, String path) {
        JsonNode n = at(o, path);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() && !n.isTextual() ? null : (int) Math.round(n.asDouble());
    }

    private static Integer integer(JsonNode o, String path) {
        return intOrNull(at(o, path));
    }

    private static Integer positive(Integer v) {
        return v == null || v <= 0 ? null : v;
    }

    private static BigDecimal decimal(JsonNode o, String path) {
        JsonNode n = at(o, path);
        return n == null || n.isNull() ? null : BigDecimal.valueOf(n.asDouble()).setScale(1, RoundingMode.HALF_UP);
    }

    private static Integer firstInt(JsonNode o, List<String> paths) {
        for (String p : paths) {
            Integer v = integer(o, p);
            if (v != null) return v;
        }
        return null;
    }

    private static BigDecimal firstDec(JsonNode o, List<String> paths) {
        for (String p : paths) {
            BigDecimal v = decimal(o, p);
            if (v != null && v.signum() > 0) return v;
        }
        return null;
    }

    private static BigDecimal positiveDec(JsonNode o, String path, int scale) {
        JsonNode n = at(o, path);
        if (n == null || n.isNull() || !n.isNumber()) return null;
        double v = n.asDouble();
        return v > 0 ? BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal dec(Float f, int scale) {
        return f == null ? null : BigDecimal.valueOf(f).setScale(scale, RoundingMode.HALF_UP);
    }

    static LocalDate date(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isObject()) n = n.get("date");
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null;
    }

    /** Garmin "GMT" timestamps: ISO local date-times in UTC, or epoch milliseconds. */
    static Instant gmt(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.chars().allMatch(c -> Character.isDigit(c) || c == '.')) return TimeParsing.epochMillis(Double.parseDouble(s));
        String t = s.endsWith("Z") ? s.substring(0, s.length() - 1) : s;
        return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC);
    }
}
