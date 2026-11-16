package app.training.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Daily Recovery, Strain and Sleep from {@code day_features} rows and a model's coefficients:
 * ADR 0008 rules S1–S8, nothing else. Pure arithmetic, no database, so the parity test can run
 * it on the committed fixtures. Every number here comes from the coefficients; the scorer adds
 * no sports-science constants of its own.
 */
public final class DailyScorer {
    private DailyScorer() {}

    /**
     * One stored {@code day_features} row.
     *
     * @param values column name → value as stored (null when the column is NULL)
     */
    public record DayInput(LocalDate date, boolean hasGarminCore, Map<String, Double> values) {
        Double get(String column) {
            return values.get(column);
        }
    }

    /** Why recovery is blank (ADR 0008 S6); {@code detail} carries yesterday's strain reason for NO_STRAIN_YESTERDAY. */
    public record Reason(String reason, String detail) {}

    /** An input outside the p01–p99 spread of the days that part of the model was fitted on. */
    public record Drift(String part, String input, double value, double p01, double p99) {}

    /** The values as stored in derived_day_metrics (S7: half-up on the shortest decimal form). */
    public record Stored(BigDecimal strain, Integer sleepNeed, Integer sleepPerf, Integer recovery) {}

    /**
     * Everything the scorer worked out for one grid day, unrounded.
     *
     * @param contributions recovery points per term, in the contract's order; null where the input is blank
     * @param loads the strain inputs that were used (for the explanation)
     */
    public record DayScore(
            LocalDate date,
            boolean hasRow,
            Double strainRaw,
            Double strain,
            String strainReason,
            boolean lowCoverage,
            Double prevStrain,
            Double shortfall,
            Double sleepNeed,
            Double sleepPerf,
            Map<String, Double> contributions,
            Map<String, Double> inputs,
            Double recoveryRaw,
            Double recovery,
            List<Reason> recoveryReasons,
            boolean recoveryLowerConfidence,
            String band,
            Stored stored,
            Map<String, Object> loads,
            List<Drift> drift) {}

    /** S6: one reason per missing recovery input, listed in this order. */
    static final List<String> REASON_ORDER = List.of(
            "NO_DAY_DATA", "NO_HRV", "NO_RESTING_HR", "NO_RESPIRATION", "NO_SLEEP", "NO_STRAIN_YESTERDAY",
            "NO_GARMIN_CORE", "NO_SPO2", "NO_BODY_BATTERY");
    static final Map<String, String> REASON_FOR_INPUT = Map.of(
            "ln_rmssd_z", "NO_HRV",
            "rhr_dev", "NO_RESTING_HR",
            "resp_dev", "NO_RESPIRATION",
            ScoringContract.SLEEP_PERF, "NO_SLEEP",
            ScoringContract.PREV_STRAIN, "NO_STRAIN_YESTERDAY",
            "sleep_spo2", "NO_SPO2",
            "body_battery_high", "NO_BODY_BATTERY");
    public static final String LOW_COVERAGE_YESTERDAY = "LOW_COVERAGE_YESTERDAY";

    /**
     * Scores every day of the grid (S1): consecutive dates from the first to the last row. A date
     * without a row is scored as a day whose every input is missing. Rows may come in any order.
     */
    public static List<DayScore> score(List<DayInput> rows, ScoringContract c) {
        if (rows.isEmpty()) return List.of();
        var byDate = new TreeMap<LocalDate, DayInput>();
        rows.forEach(r -> byDate.put(r.date(), r));
        LocalDate first = byDate.firstKey();
        LocalDate last = byDate.lastKey();

        var out = new ArrayList<DayScore>();
        var sleepHistory = new ArrayList<Double>(); // deficit per grid day, oldest first (S4)
        Double prevStrain = null;
        String prevReason = "NO_DAY_DATA"; // the day before the first grid day has no row
        boolean prevLow = false;
        var sc = c.strain();
        var sl = c.sleep();
        var rc = c.recovery();

        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            DayInput row = byDate.get(day);
            boolean present = row != null;

            // S2 strain
            var loads = new LinkedHashMap<String, Object>();
            String strainReason = strainReason(row, sc);
            Double strainRaw = null;
            if (strainReason == null) {
                strainRaw = rawStrain(row, sc, loads);
            }
            Double strain = strainRaw == null ? null : clip(strainRaw, sc.clipLow(), sc.clipHigh());
            Double monitored = present ? row.get("monitored_minutes") : null;
            boolean lowCoverage = (monitored == null ? 0.0 : monitored) < sc.minMonitoredMinutes();
            if (monitored != null) loads.put("monitored_minutes", monitored);

            // S4 shortfall over the previous N grid days; missing nights add 0
            double shortfall = 0;
            for (int i = Math.max(0, sleepHistory.size() - sl.shortfallDays()); i < sleepHistory.size(); i++) {
                shortfall += sleepHistory.get(i);
            }
            Double sleepMinutes = present ? row.get("sleep_minutes") : null;
            sleepHistory.add(sleepMinutes == null ? 0.0 : Math.max(0.0, sl.shortfallReferenceMin() - sleepMinutes));

            // S5 sleep need (blank yesterday's strain counts as 0, L1) and performance (L3)
            double need = sl.baseMin() + sl.perStrainMin() * (prevStrain == null ? 0.0 : prevStrain) + sl.perShortfall() * shortfall;
            Double sleepPerf = sleepMinutes == null || !(need > 0) ? null
                    : clip(100 * sleepMinutes / need, sl.perfClipLow(), sl.perfClipHigh());

            // S6 recovery
            var inputs = new LinkedHashMap<String, Double>();
            var contributions = new LinkedHashMap<String, Double>();
            var reasons = new ArrayList<Reason>();
            double sum = 0;
            for (var t : rc.terms()) {
                Double x = switch (t.feature()) {
                    case ScoringContract.PREV_STRAIN -> prevStrain;
                    case ScoringContract.SLEEP_PERF -> sleepPerf;
                    default -> present ? row.get(t.feature()) : null;
                };
                inputs.put(t.feature(), x);
                if (x == null) {
                    contributions.put(t.feature(), null);
                    if (present) {
                        String reason = REASON_FOR_INPUT.getOrDefault(t.feature(), "NO_" + t.feature().toUpperCase(java.util.Locale.ROOT));
                        reasons.add(new Reason(reason, reason.equals("NO_STRAIN_YESTERDAY") ? prevReason : null));
                    }
                } else {
                    double p = t.weight() * (t.sign() * x - t.center()) / t.scale();
                    contributions.put(t.feature(), p);
                    sum += p;
                }
            }
            if (!present) {
                reasons.add(new Reason("NO_DAY_DATA", null));
            } else if (reasons.isEmpty() && !row.hasGarminCore()) {
                reasons.add(new Reason("NO_GARMIN_CORE", null));
            }
            reasons.sort(Comparator.comparingInt(r -> {
                int i = REASON_ORDER.indexOf(r.reason());
                return i < 0 ? REASON_ORDER.size() : i;
            }));
            Double recoveryRaw = reasons.isEmpty() ? rc.intercept() + sum : null;
            Double recovery = recoveryRaw == null ? null : clip(recoveryRaw, rc.clipLow(), rc.clipHigh());
            boolean lowerConfidence = recovery != null && prevLow; // S8
            String band = recovery == null ? null : band(recovery, c.greenMin(), c.redMax()); // S7: unrounded

            loads.put(ScoringContract.PREV_STRAIN, prevStrain);
            loads.put(ScoringContract.SHORTFALL, shortfall);

            var drift = drift(c, strain, row, prevStrain, shortfall, recovery, inputs);
            var stored = new Stored(
                    strain == null ? null : BigDecimal.valueOf(strain).setScale(1, RoundingMode.HALF_UP),
                    roundInt(need),
                    sleepPerf == null ? null : roundInt(sleepPerf),
                    recovery == null ? null : roundInt(recovery));

            out.add(new DayScore(day, present, strainRaw, strain, strainReason, lowCoverage, prevStrain, shortfall, need, sleepPerf,
                    Collections.unmodifiableMap(contributions), Collections.unmodifiableMap(inputs), recoveryRaw, recovery,
                    List.copyOf(reasons), lowerConfidence, band, stored, Collections.unmodifiableMap(loads), drift));

            prevStrain = strain;
            prevReason = strainReason;
            prevLow = lowCoverage;
        }
        return out;
    }

    /** S2: why strain is blank, or null when it can be computed. Missing values are never 0. */
    static String strainReason(DayInput row, ScoringContract.Strain s) {
        if (row == null) return "NO_DAY_DATA";
        if (s.form() == ScoringContract.Form.ZONES) {
            for (String z : ScoringContract.ZONE_COLUMNS) {
                if (row.get(z) == null) return "NO_ZONE_MINUTES";
            }
            return null;
        }
        if (row.get(s.loadColumn()) == null) return "MISSING_WORKOUT_HR";
        if (s.usesBackground() && row.get(ScoringContract.BACKGROUND) == null) return "NO_ALL_DAY_HR"; // even if k = 0 (L5)
        return null;
    }

    /** S2 formulas, before clipping. Records the inputs it used in {@code loads}. */
    private static double rawStrain(DayInput row, ScoringContract.Strain s, Map<String, Object> loads) {
        if (s.form() == ScoringContract.Form.ZONES) {
            double weighted = 0;
            var minutes = new ArrayList<Double>();
            for (int i = 0; i < ScoringContract.ZONE_COLUMNS.size(); i++) {
                double m = row.get(ScoringContract.ZONE_COLUMNS.get(i));
                minutes.add(m);
                weighted += s.zoneWeights().get(i) * m;
            }
            loads.put("zone_minutes", List.copyOf(minutes));
            loads.put("weighted_zone_minutes", weighted);
            return s.c() + s.a() * Math.log1p(weighted);
        }
        double load = row.get(s.loadColumn());
        double bg = s.usesBackground() ? row.get(ScoringContract.BACKGROUND) : 0.0;
        double combined = load + s.backgroundWeight() * bg;
        loads.put("load_column", s.loadColumn());
        loads.put("workout_load", load);
        if (s.usesBackground()) loads.put("background_load", bg);
        loads.put("combined_load", combined);
        return s.c() + s.a() * Math.log1p(s.b() * combined);
    }

    /**
     * Inputs outside training_ranges p01–p99, for the parts that produced a value today: strain
     * when strain was scored, sleep need always (it is never blank), recovery when it was scored.
     */
    private static List<Drift> drift(ScoringContract c, Double strain, DayInput row, Double prevStrain, double shortfall,
                                     Double recovery, Map<String, Double> recoveryInputs) {
        var out = new ArrayList<Drift>();
        if (strain != null) {
            c.trainingRanges().get("strain").forEach((input, r) -> check(out, "strain", input, row.get(input), r));
        }
        c.trainingRanges().get("sleep").forEach((input, r) -> check(out, "sleep", input,
                switch (input) {
                    case ScoringContract.PREV_STRAIN -> prevStrain;
                    case ScoringContract.SHORTFALL -> shortfall;
                    default -> null;
                }, r));
        if (recovery != null) {
            c.trainingRanges().get("recovery").forEach((input, r) -> check(out, "recovery", input, recoveryInputs.get(input), r));
        }
        return List.copyOf(out);
    }

    private static void check(List<Drift> out, String part, String input, Double value, ScoringContract.Range r) {
        if (value != null && (value < r.p01() || value > r.p99())) {
            out.add(new Drift(part, input, value, r.p01(), r.p99()));
        }
    }

    static String band(double recovery, double greenMin, double redMax) {
        if (recovery >= greenMin) return "GREEN";
        if (recovery <= redMax) return "RED";
        return "YELLOW";
    }

    private static double clip(double x, double lo, double hi) {
        return Math.min(hi, Math.max(lo, x));
    }

    private static int roundInt(double x) {
        return BigDecimal.valueOf(x).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
