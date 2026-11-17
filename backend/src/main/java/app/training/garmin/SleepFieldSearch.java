package app.training.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Part of "Check against export" (ADR 0015 D11): where in Garmin's live sleep answer are the
 * values the export stores? For every number in the answer (and the mean of every numeric field
 * of a list), it counts the nights on which that number, rounded like the stored column, equals
 * the stored export value. The result is field paths and counts only, never values, so the coach
 * can pass it on and the translation can be pinned to the right field.
 */
public final class SleepFieldSearch {
    private SleepFieldSearch() {}

    public record Candidate(String path, int matches) {}

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
            int nights = 0;
            for (var e : stored.entrySet()) {
                BigDecimal want = e.getValue().get(column);
                Map<String, Double> candidates = paths.get(e.getKey());
                if (want == null || candidates == null) continue;
                nights++;
                candidates.forEach((path, v) -> {
                    if (BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).compareTo(want) == 0) matches.merge(path, 1, Integer::sum);
                });
            }
            var top = matches.entrySet().stream()
                    .map(m -> new Candidate(m.getKey(), m.getValue()))
                    .sorted(Comparator.comparingInt(Candidate::matches).reversed().thenComparing(Candidate::path))
                    .limit(TOP)
                    .toList();
            out.add(new Result(column, nights, top));
        });
        return out;
    }

    /**
     * Every number in {@code root} by its path ("a.b"), plus for a list of objects the mean of each
     * numeric field ("a[].f (mean)") and for a list of number pairs the mean of each position
     * ("a[][1] (mean)"). Zero and negative numbers are left out of the means: Garmin writes them
     * as placeholders for "no reading".
     */
    static Map<String, Double> numericPaths(JsonNode root) {
        var out = new LinkedHashMap<String, Double>();
        walk(root, "", out);
        return out;
    }

    private static void walk(JsonNode node, String path, Map<String, Double> out) {
        if (node == null) return;
        if (node.isNumber()) {
            out.put(path, node.asDouble());
        } else if (node.isObject()) {
            for (var f : node.properties()) {
                walk(f.getValue(), path.isEmpty() ? f.getKey() : path + "." + f.getKey(), out);
            }
        } else if (node.isArray() && !node.isEmpty()) {
            var sums = new LinkedHashMap<String, double[]>();
            for (JsonNode el : node) {
                if (el.isObject()) {
                    for (var f : el.properties()) {
                        if (f.getValue().isNumber()) add(sums, path + "[]." + f.getKey() + " (mean)", f.getValue().asDouble());
                    }
                } else if (el.isArray()) {
                    for (int k = 0; k < el.size(); k++) {
                        if (el.get(k).isNumber()) add(sums, path + "[][" + k + "] (mean)", el.get(k).asDouble());
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

    private static void add(Map<String, double[]> sums, String key, double v) {
        if (v <= 0) return;
        double[] s = sums.computeIfAbsent(key, k -> new double[2]);
        s[0] += v;
        s[1]++;
    }
}
