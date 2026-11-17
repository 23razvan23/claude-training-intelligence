package app.training.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Part of "Check against export" (ADR 0015 D11): where in Garmin's live sleep answer are the
 * values the export stores? Candidates are every number in the answer, the mean of every numeric
 * field of a list, and the same mean over the time asleep only (list entries whose timestamp lies
 * between the night's start and end; the live answer pads the night with awake time). For each
 * candidate it counts the nights on which it, rounded like the stored column, equals the stored
 * export value, and how far off it is on average. The result is field paths and counts or
 * differences only, never values.
 */
public final class SleepFieldSearch {
    private SleepFieldSearch() {}

    /**
     * @param matches nights on which the candidate, rounded like the column, equals the stored value
     * @param nights nights on which the candidate exists (out of those with a stored value)
     * @param meanAbsDiff average of |candidate − stored| over those nights, rounded to 2 decimals
     */
    public record Candidate(String path, int matches, int nights, BigDecimal meanAbsDiff) {}

    public record Result(String column, int nights, List<Candidate> candidates) {}

    /** Columns searched, with the scale they are stored at. */
    static final Map<String, Integer> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("avg_respiration", 1);
        COLUMNS.put("avg_spo2_pct", 1);
        COLUMNS.put("lowest_spo2_pct", 1);
        COLUMNS.put("avg_sleep_stress", 2);
    }

    static final int TOP = 6;
    /** Fields read as a list entry's time, in order: epoch ms or Garmin's "yyyy-MM-ddTHH:mm:ss.S" GMT text. */
    static final List<String> TIME_FIELDS = List.of("startGMT", "startTimeGMT", "epochTimestamp", "startTimestampGMT", "timestampGMT");

    /**
     * @param answers the live sleep answer per night
     * @param stored the stored (export) values per night, by column
     */
    static List<Result> search(Map<String, JsonNode> answers, Map<String, Map<String, BigDecimal>> stored) {
        var paths = new HashMap<String, Map<String, Double>>();
        answers.forEach((night, answer) -> paths.put(night, numericPaths(answer)));
        var out = new ArrayList<Result>();
        COLUMNS.forEach((column, scale) -> {
            var matches = new HashMap<String, Integer>();
            var present = new HashMap<String, Integer>();
            var absDiff = new HashMap<String, Double>();
            int nights = 0;
            for (var e : stored.entrySet()) {
                BigDecimal want = e.getValue().get(column);
                Map<String, Double> candidates = paths.get(e.getKey());
                if (want == null || candidates == null) continue;
                nights++;
                candidates.forEach((path, v) -> {
                    present.merge(path, 1, Integer::sum);
                    absDiff.merge(path, Math.abs(v - want.doubleValue()), Double::sum);
                    if (BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).compareTo(want) == 0) matches.merge(path, 1, Integer::sum);
                });
            }
            int total = nights;
            var top = present.entrySet().stream()
                    // A candidate seen on fewer than half the nights tells nothing.
                    .filter(p -> p.getValue() * 2 >= total)
                    .map(p -> new Candidate(p.getKey(), matches.getOrDefault(p.getKey(), 0), p.getValue(),
                            BigDecimal.valueOf(absDiff.get(p.getKey()) / p.getValue()).setScale(2, RoundingMode.HALF_UP)))
                    .sorted(Comparator.comparingInt(Candidate::matches).reversed()
                            .thenComparing(Candidate::meanAbsDiff).thenComparing(Candidate::path))
                    .limit(TOP)
                    .toList();
            out.add(new Result(column, nights, top));
        });
        return out;
    }

    /**
     * Every number in {@code root} by its path ("a.b"); for a list of objects the mean of each
     * numeric field ("a[].f (mean)") and, when entries carry a time, the mean over the time asleep
     * ("a[].f (mean, asleep)"); for a list of number pairs the mean of each position ("a[][1] (mean)",
     * and "(mean, asleep)" when position 0 is an epoch-ms time). Zero and negative numbers are left
     * out of the means: Garmin writes them as placeholders for "no reading".
     */
    static Map<String, Double> numericPaths(JsonNode root) {
        var out = new LinkedHashMap<String, Double>();
        JsonNode dto = root.path("dailySleepDTO");
        Long start = epochMs(dto.get("sleepStartTimestampGMT"));
        Long end = epochMs(dto.get("sleepEndTimestampGMT"));
        walk(root, "", out, start, end);
        return out;
    }

    private static void walk(JsonNode node, String path, Map<String, Double> out, Long start, Long end) {
        if (node == null) return;
        if (node.isNumber()) {
            out.put(path, node.asDouble());
        } else if (node.isObject()) {
            for (var f : node.properties()) {
                walk(f.getValue(), path.isEmpty() ? f.getKey() : path + "." + f.getKey(), out, start, end);
            }
        } else if (node.isArray() && !node.isEmpty()) {
            var sums = new LinkedHashMap<String, double[]>();
            for (JsonNode el : node) {
                if (el.isObject()) {
                    Long t = null;
                    for (String tf : TIME_FIELDS) if (t == null) t = epochMs(el.get(tf));
                    boolean asleep = t != null && start != null && end != null && t >= start && t <= end;
                    for (var f : el.properties()) {
                        if (!f.getValue().isNumber() || TIME_FIELDS.contains(f.getKey())) continue;
                        add(sums, path + "[]." + f.getKey() + " (mean)", f.getValue().asDouble());
                        if (asleep) add(sums, path + "[]." + f.getKey() + " (mean, asleep)", f.getValue().asDouble());
                    }
                } else if (el.isArray()) {
                    Long t = el.size() > 0 && el.get(0).isNumber() && el.get(0).asLong() > 1_000_000_000_000L ? el.get(0).asLong() : null;
                    boolean asleep = t != null && start != null && end != null && t >= start && t <= end;
                    for (int k = t == null ? 0 : 1; k < el.size(); k++) {
                        if (!el.get(k).isNumber()) continue;
                        add(sums, path + "[][" + k + "] (mean)", el.get(k).asDouble());
                        if (asleep) add(sums, path + "[][" + k + "] (mean, asleep)", el.get(k).asDouble());
                    }
                } else if (el.isNumber()) {
                    add(sums, path + "[] (mean)", el.asDouble());
                }
            }
            sums.forEach((p, s) -> {
                if (s[1] > 0) out.put(p, s[0] / s[1]);
            });
        }
    }

    /** Epoch ms from a number, or from Garmin's GMT text ("2026-11-15T23:01:00.0"); null otherwise. */
    static Long epochMs(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.asLong() > 1_000_000_000_000L ? n.asLong() : null;
        if (n.isTextual()) {
            String s = n.asText().strip();
            if (s.endsWith("Z")) s = s.substring(0, s.length() - 1);
            try {
                return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException e) {
                try {
                    return Instant.parse(n.asText()).toEpochMilli();
                } catch (DateTimeParseException e2) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void add(Map<String, double[]> sums, String key, double v) {
        if (v <= 0) return;
        double[] s = sums.computeIfAbsent(key, k -> new double[2]);
        s[0] += v;
        s[1]++;
    }
}
