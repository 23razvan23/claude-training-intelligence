package app.training.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A model version's coefficients, read strictly (ADR 0008; calibration/contract.schema.json,
 * schema_version 2). Anything the scorer would have to guess about is refused with
 * {@link Invalid}, so a contract it does not understand never produces scores.
 */
public record ScoringContract(
        String calibrationVersion,
        String featureVersion,
        Integer hrMax,
        Integer hrRest,
        Strain strain,
        Sleep sleep,
        Recovery recovery,
        double greenMin,
        double redMax,
        Map<String, Map<String, Range>> trainingRanges) {

    public static final int SCHEMA_VERSION = ModelVersion.SUPPORTED_SCHEMA_VERSION;

    /** The six whole-day zone columns, in order (zones form). */
    public static final List<String> ZONE_COLUMNS = List.of("zone_min_0", "zone_min_1", "zone_min_2", "zone_min_3", "zone_min_4", "zone_min_5");
    static final Set<String> LOAD_COLUMNS = Set.of("day_load_banister", "day_load_edwards");
    static final String BACKGROUND = "background_load";

    /** Recovery inputs read from day_features (ADR 0008 S6). */
    static final Set<String> FEATURE_INPUTS = Set.of("ln_rmssd_z", "rhr_dev", "resp_dev", "sleep_spo2", "body_battery_high");
    /** Recovery inputs derived by the scorer itself (S3, S5). */
    static final String PREV_STRAIN = "prev_strain";
    static final String SLEEP_PERF = "sleep_perf";
    static final String SHORTFALL = "shortfall";

    public enum Form { LOAD, ZONES }

    /**
     * @param loadColumn day_features column of the workout load (load form only)
     * @param zoneWeights one weight per {@link #ZONE_COLUMNS} entry (zones form only)
     */
    public record Strain(Form form, String loadColumn, double a, double b, double c, double backgroundWeight,
                         boolean usesBackground, List<Double> zoneWeights, int minMonitoredMinutes,
                         double clipLow, double clipHigh) {}

    public record Sleep(double baseMin, double perStrainMin, double perShortfall, double shortfallReferenceMin,
                        int shortfallDays, double perfClipLow, double perfClipHigh) {}

    public record Term(String feature, int sign, double weight, double center, double scale) {}

    public record Recovery(double intercept, List<Term> terms, double clipLow, double clipHigh) {}

    public record Range(double min, double p01, double p99, double max, int n) {}

    /** The coefficients cannot be scored with; the message says why in plain words. */
    public static class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    public static ScoringContract parse(JsonNode c) {
        if (c == null || !c.isObject()) throw new Invalid("The coefficients are not a JSON object.");
        var v = c.get("schema_version");
        if (v == null || !v.isIntegralNumber() || v.asInt() != SCHEMA_VERSION) {
            throw new Invalid("Unsupported coefficients schema_version " + (v == null ? "(missing)" : v.toString())
                    + "; this app reads only " + SCHEMA_VERSION + ". Run a new calibration and activate it.");
        }
        var fc = obj(c, "feature_config");
        var bands = obj(c, "bands");
        return new ScoringContract(
                text(c, "calibration_version"),
                text(c, "feature_version"),
                optInt(fc, "hr_max"),
                optInt(fc, "hr_rest"),
                strain(obj(c, "strain")),
                sleep(obj(c, "sleep")),
                recovery(obj(c, "recovery")),
                num(bands, "green_min"),
                num(bands, "red_max"),
                ranges(obj(c, "training_ranges")));
    }

    private static Strain strain(JsonNode s) {
        String form = text(s, "form");
        double[] clip = interval(s, "clip");
        int minMonitored = integer(s, "min_monitored_minutes");
        switch (form) {
            case "load" -> {
                String load = text(s, "load");
                if (!LOAD_COLUMNS.contains(load)) throw new Invalid("Unknown strain load column '" + load + "'.");
                return new Strain(Form.LOAD, load, num(s, "a"), num(s, "b"), num(s, "c"), nonNegative(s, "background_weight"),
                        bool(s, "uses_background"), List.of(), minMonitored, clip[0], clip[1]);
            }
            case "zones" -> {
                var cols = s.get("zone_columns");
                var weights = s.get("zone_weights");
                if (cols == null || !cols.isArray() || weights == null || !weights.isArray()
                        || cols.size() != ZONE_COLUMNS.size() || weights.size() != ZONE_COLUMNS.size()) {
                    throw new Invalid("The zones strain form needs six zone columns and six weights.");
                }
                var w = new ArrayList<Double>();
                for (int i = 0; i < ZONE_COLUMNS.size(); i++) {
                    if (!ZONE_COLUMNS.get(i).equals(cols.get(i).asText())) {
                        throw new Invalid("Unexpected zone column '" + cols.get(i).asText() + "'.");
                    }
                    if (!weights.get(i).isNumber() || weights.get(i).asDouble() < 0) throw new Invalid("Zone weights must be numbers ≥ 0.");
                    w.add(weights.get(i).asDouble());
                }
                return new Strain(Form.ZONES, null, num(s, "a"), 0, num(s, "c"), 0, false, List.copyOf(w), minMonitored, clip[0], clip[1]);
            }
            default -> throw new Invalid("Unknown strain form '" + form + "'.");
        }
    }

    private static Sleep sleep(JsonNode s) {
        double[] clip = interval(s, "performance_clip");
        int days = integer(s, "shortfall_days");
        if (days < 1) throw new Invalid("sleep.shortfall_days must be at least 1.");
        return new Sleep(nonNegative(s, "base_min"), nonNegative(s, "per_strain_min"), nonNegative(s, "per_shortfall"),
                num(s, "shortfall_reference_min"), days, clip[0], clip[1]);
    }

    private static Recovery recovery(JsonNode r) {
        var terms = r.get("terms");
        if (terms == null || !terms.isArray() || terms.isEmpty()) throw new Invalid("recovery.terms is missing or empty.");
        var out = new ArrayList<Term>();
        for (JsonNode t : terms) {
            String f = text(t, "feature");
            if (!FEATURE_INPUTS.contains(f) && !f.equals(PREV_STRAIN) && !f.equals(SLEEP_PERF)) {
                throw new Invalid("Unknown recovery input '" + f + "'.");
            }
            int sign = integer(t, "sign");
            if (sign != 1 && sign != -1) throw new Invalid("Recovery term sign must be 1 or -1.");
            double scale = num(t, "scale");
            if (!(scale > 0)) throw new Invalid("Recovery term scale must be > 0.");
            out.add(new Term(f, sign, nonNegative(t, "weight"), num(t, "center"), scale));
        }
        double[] clip = interval(r, "clip");
        return new Recovery(num(r, "intercept"), List.copyOf(out), clip[0], clip[1]);
    }

    private static Map<String, Map<String, Range>> ranges(JsonNode tr) {
        var out = new LinkedHashMap<String, Map<String, Range>>();
        for (String part : List.of("strain", "sleep", "recovery")) {
            var p = obj(tr, part);
            var m = new LinkedHashMap<String, Range>();
            p.properties().forEach(e -> {
                var x = e.getValue();
                if (!x.isObject()) throw new Invalid("training_ranges." + part + "." + e.getKey() + " is not an object.");
                m.put(e.getKey(), new Range(num(x, "min"), num(x, "p01"), num(x, "p99"), num(x, "max"), integer(x, "n")));
            });
            out.put(part, Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableMap(out);
    }

    private static JsonNode obj(JsonNode c, String key) {
        var n = c.get(key);
        if (n == null || !n.isObject()) throw new Invalid("The coefficients have no '" + key + "' block.");
        return n;
    }

    private static String text(JsonNode c, String key) {
        var n = c.get(key);
        if (n == null || !n.isTextual() || n.asText().isEmpty()) throw new Invalid("'" + key + "' is missing.");
        return n.asText();
    }

    private static double num(JsonNode c, String key) {
        var n = c.get(key);
        if (n == null || !n.isNumber() || !Double.isFinite(n.asDouble())) throw new Invalid("'" + key + "' is missing or not a number.");
        return n.asDouble();
    }

    private static double nonNegative(JsonNode c, String key) {
        double x = num(c, key);
        if (x < 0) throw new Invalid("'" + key + "' must be ≥ 0.");
        return x;
    }

    private static int integer(JsonNode c, String key) {
        var n = c.get(key);
        if (n == null || !n.isIntegralNumber()) throw new Invalid("'" + key + "' is missing or not a whole number.");
        return n.asInt();
    }

    private static Integer optInt(JsonNode c, String key) {
        var n = c.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isIntegralNumber()) throw new Invalid("'" + key + "' is not a whole number.");
        return n.asInt();
    }

    private static boolean bool(JsonNode c, String key) {
        var n = c.get(key);
        if (n == null || !n.isBoolean()) throw new Invalid("'" + key + "' is missing or not true/false.");
        return n.asBoolean();
    }

    private static double[] interval(JsonNode c, String key) {
        var n = c.get(key);
        if (n == null || !n.isArray() || n.size() != 2 || !n.get(0).isNumber() || !n.get(1).isNumber()
                || n.get(0).asDouble() > n.get(1).asDouble()) {
            throw new Invalid("'" + key + "' must be [low, high].");
        }
        return new double[] {n.get(0).asDouble(), n.get(1).asDouble()};
    }
}
