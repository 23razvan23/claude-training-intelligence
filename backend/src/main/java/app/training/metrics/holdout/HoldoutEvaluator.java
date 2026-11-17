package app.training.metrics.holdout;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The live comparison with Whoop (ADR 0009), as pure arithmetic: one model version's stored
 * daily scores against Whoop's finished cycles, judged with the pass marks frozen at that
 * version's activation. No database, no clock: {@link HoldoutService} gathers the inputs.
 *
 * <p>Every day of the window is counted once per score, in exactly one group (H4):
 * {@code COMPARED} (strain also {@code COMPARED_LOW_COVERAGE}), {@code NO_WHOOP},
 * {@code NO_SCORE_YET} (after our last data date), or our own ADR 0008 reason. Order of
 * precedence when several apply: no Whoop, then no score yet, then our reason (ADR 0013 D3).
 * A day that would be compared but whose score was calculated from a night that came from a
 * Garmin sync goes to {@code WAITS_FOR_EXPORT} instead, for every score, until an exported night
 * replaces it and the day is rescored (ADR 0015 D13).
 */
public final class HoldoutEvaluator {
    private HoldoutEvaluator() {}

    public static final String COMPARED = "COMPARED";
    public static final String COMPARED_LOW_COVERAGE = "COMPARED_LOW_COVERAGE";
    public static final String NO_WHOOP = "NO_WHOOP";
    public static final String NO_SCORE_YET = "NO_SCORE_YET";
    /** Scored from a synced night (Garmin's live breathing rate): not counted until an export replaces it (ADR 0015 D13). */
    public static final String WAITS_FOR_EXPORT = "WAITS_FOR_EXPORT";

    // ---------------------------------------------------------------- inputs

    /**
     * The pass marks and protocol values frozen in {@code model_version.holdout_targets} (H3).
     * Nothing here is read from anywhere else.
     */
    public record Marks(
            double recoveryBandAgreementMin, double recoveryGreenRedMax, double strainMaeMax, double strainSpearmanMin,
            double strainMinMonitoredMinutes, int minRecoveryDays, double confidence, String interval,
            LocalDate windowFrom, String protocol, String source) {

        /** Reads the frozen JSON. Throws when a mark is missing: the comparison then can't be judged. */
        public static Marks of(JsonNode t) {
            if (t == null || !t.isObject()) {
                throw new IllegalArgumentException("no frozen holdout targets");
            }
            return new Marks(num(t, "recovery_band_agreement_min"), num(t, "recovery_green_red_max"), num(t, "strain_mae_max"),
                    num(t, "strain_spearman_min"), num(t, "strain_min_monitored_minutes"), (int) num(t, "min_recovery_days"),
                    num(t, "confidence"), text(t, "interval"), LocalDate.parse(text(t, "window_from")), text(t, "protocol"),
                    text(t, "source"));
        }

        private static double num(JsonNode t, String key) {
            var v = t.get(key);
            if (v == null || !v.isNumber()) {
                throw new IllegalArgumentException("holdout target '" + key + "' is missing");
            }
            return v.asDouble();
        }

        private static String text(JsonNode t, String key) {
            var v = t.get(key);
            if (v == null || !v.isTextual()) {
                throw new IllegalArgumentException("holdout target '" + key + "' is missing");
            }
            return v.asText();
        }
    }

    /** One finished Whoop cycle, by its wake day (ADR 0002). */
    public record WhoopDay(LocalDate date, Integer recovery, Double strain, Integer sleepPerf, Integer sleepNeedMin) {}

    /**
     * One of our stored daily scores (derived_day_metrics), reduced to what the comparison reads.
     *
     * @param recovery unrounded, clipped recovery (for MAE and Spearman, as in calibration); null when blank
     * @param recoveryPct the stored, rounded recovery shown to the coach
     * @param band from the unrounded recovery (ADR 0008 S7)
     * @param recoveryReason first ADR 0008 S6 reason when recovery is blank, with its detail after a "/" (e.g. NO_STRAIN_YESTERDAY/MISSING_WORKOUT_HR)
     * @param strain unrounded, clipped strain; null when blank
     * @param strainShown the stored strain (one decimal) shown to the coach
     * @param sleepPerfPct the stored sleep performance shown to the coach
     * @param monitoredMinutes all-day HR minutes, null when unknown (treated as 0)
     * @param sleepNeed unrounded sleep need (minutes), never null for a stored row
     */
    public record OurDay(
            LocalDate date, Double recovery, Integer recoveryPct, String band, String recoveryReason, boolean lowerConfidence,
            Double strain, Double strainShown, String strainReason, Double monitoredMinutes, Double sleepPerf, Integer sleepPerfPct,
            String sleepPerfReason, Double sleepNeed, boolean profileChanged, JsonNode profile, boolean staleFeatures) {}

    /** Where the comparison stands: still running, ended because Whoop ended, or ended by a new activation (H8). */
    public enum Phase { RUNNING, ENDED, RETIRED }

    /**
     * Everything the comparison needs.
     *
     * @param whoop finished cycles from the day before the window on (the naive baseline reads the day before)
     * @param whoopEnd the last day of the Whoop subscription
     * @param today the athlete's local date; decides whether the comparison has ended
     * @param rollingDays length of the drift window ending on the last Whoop day
     * @param recomputeRuns when Recompute features was pressed on or after the window's first day (H8 markers)
     * @param syncedNights days whose stored score was calculated from a night that came from a Garmin sync (ADR 0015 D13)
     */
    public record Input(
            String modelVersion, String status, Instant activatedAt, Instant retiredAt, JsonNode frozen, Marks marks,
            double greenMin, double redMax, List<OurDay> ours, List<WhoopDay> whoop, LocalDate whoopEnd, LocalDate today,
            int rollingDays, List<Instant> recomputeRuns, java.util.Set<LocalDate> syncedNights) {

        public Input {
            syncedNights = syncedNights == null ? java.util.Set.of() : java.util.Set.copyOf(syncedNights);
        }

        /** Without synced nights (every night exported). */
        public Input(String modelVersion, String status, Instant activatedAt, Instant retiredAt, JsonNode frozen, Marks marks,
                     double greenMin, double redMax, List<OurDay> ours, List<WhoopDay> whoop, LocalDate whoopEnd, LocalDate today,
                     int rollingDays, List<Instant> recomputeRuns) {
            this(modelVersion, status, activatedAt, retiredAt, frozen, marks, greenMin, redMax, ours, whoop, whoopEnd, today,
                    rollingDays, recomputeRuns, java.util.Set.of());
        }
    }

    // ---------------------------------------------------------------- output

    /** A mean absolute difference (ours − Whoop) or Spearman correlation, always with its n; value null below n = 1 (Spearman: n < 3 or no spread). */
    public record Stat(int n, Double value) {}

    public record RecoveryFigures(
            int n, Wilson.Rate bandAgreement, Wilson.Rate greenRed, Stat mae, Stat spearman, int lowerConfidence,
            Map<String, Map<String, Integer>> confusion) {}

    public record StrainFigures(int n, Stat mae, Stat spearman) {}

    public record Figures(
            LocalDate from, LocalDate to, int days, RecoveryFigures recovery, StrainFigures strain, StrainFigures strainLowCoverage,
            Stat sleepPerformanceMae, Stat sleepNeedMae, Map<String, Map<String, Integer>> groups) {}

    /** The same rates for our score and for "yesterday's Whoop", on exactly the same days (H7). */
    public record Side(Wilson.Rate bandAgreement, Wilson.Rate greenRed, Stat recoveryMae) {}

    public record Naive(int n, int skippedNoWhoopYesterday, Side ours, Side naive) {}

    /**
     * One pass mark, judged on the measured value (H6, coach decision D1 option b).
     *
     * @param met null while not judged (not enough days)
     */
    public record Mark(String key, String comparison, double target, Double value, int n, Wilson.Rate rate, Boolean met) {}

    public enum State { NOT_ENOUGH_DAYS_YET, INTERIM, FINAL, NOT_ENOUGH_DAYS_BY_RULE }

    public enum Overall { PASS, NOT_MET }

    /** @param overall null unless the state is INTERIM or FINAL */
    public record Verdict(State state, Overall overall, int recoveryDays, int minRecoveryDays, List<Mark> marks) {}

    public record DayRow(
            LocalDate date, String recoveryGroup, Integer ourRecovery, String ourBand, Integer whoopRecovery, String whoopBand,
            boolean lowerConfidence, String strainGroup, Double ourStrain, Double whoopStrain, boolean lowCoverage,
            String sleepGroup, Integer ourSleepPerf, Integer whoopSleepPerf, Integer naiveRecovery, String naiveBand) {}

    /** @param waitingForExportDays window days that would be compared but wait for a Garmin export (ADR 0015 D13) */
    public record Markers(int profileChangedDays, JsonNode profile, List<Instant> recomputeRuns, int staleFeatureDays,
                          int waitingForExportDays) {}

    public record Report(
            String modelVersion, String status, Instant activatedAt, Instant retiredAt, String protocol, String source,
            JsonNode marks, LocalDate windowFrom, LocalDate windowTo, LocalDate whoopEnd, LocalDate lastScoredDate, Phase phase,
            Verdict verdict, Figures cumulative, Figures rolling, List<Figures> weeks, Naive naive, Markers markers,
            List<DayRow> days) {}

    // ---------------------------------------------------------------- evaluation

    /** Internal per-day classification, shared by every summary. */
    private record Day(LocalDate date, OurDay ours, WhoopDay whoop, WhoopDay whoopYesterday, String recoveryGroup,
                       String strainGroup, String sleepGroup, String sleepNeedGroup) {}

    public static Report evaluate(Input in) {
        Marks m = in.marks();
        var ours = new LinkedHashMap<LocalDate, OurDay>();
        in.ours().forEach(o -> ours.put(o.date(), o));
        var whoop = new LinkedHashMap<LocalDate, WhoopDay>();
        in.whoop().forEach(w -> whoop.put(w.date(), w));
        LocalDate lastScored = in.ours().stream().map(OurDay::date).max(Comparator.naturalOrder()).orElse(null);

        // H2: from window_from to the last finished Whoop day (null: no Whoop day in the window yet).
        LocalDate windowTo = in.whoop().stream().map(WhoopDay::date).filter(d -> !d.isBefore(m.windowFrom()))
                .max(Comparator.naturalOrder()).orElse(null);

        var days = new ArrayList<Day>();
        if (windowTo != null) {
            for (LocalDate d = m.windowFrom(); !d.isAfter(windowTo); d = d.plusDays(1)) {
                days.add(classify(d, ours.get(d), whoop.get(d), whoop.get(d.minusDays(1)), m, in.syncedNights().contains(d)));
            }
        }

        Phase phase = "RETIRED".equals(in.status()) ? Phase.RETIRED
                : (in.today().isAfter(in.whoopEnd()) || (windowTo != null && !windowTo.isBefore(in.whoopEnd()))) ? Phase.ENDED
                : Phase.RUNNING;

        Figures cumulative = figures(days, m.windowFrom(), windowTo, in);
        Figures rolling = null;
        if (windowTo != null) {
            LocalDate from = windowTo.minusDays(in.rollingDays() - 1L);
            if (from.isBefore(m.windowFrom())) from = m.windowFrom();
            LocalDate f = from;
            rolling = figures(days.stream().filter(x -> !x.date().isBefore(f)).toList(), from, windowTo, in);
        }
        var weeks = new ArrayList<Figures>();
        if (windowTo != null) {
            for (LocalDate monday = m.windowFrom().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                 !monday.isAfter(windowTo); monday = monday.plusWeeks(1)) {
                LocalDate lo = monday.isBefore(m.windowFrom()) ? m.windowFrom() : monday;
                LocalDate sunday = monday.plusDays(6);
                LocalDate hi = sunday.isAfter(windowTo) ? windowTo : sunday;
                weeks.add(figures(days.stream().filter(x -> !x.date().isBefore(lo) && !x.date().isAfter(hi)).toList(), lo, hi, in));
            }
        }

        var rows = new ArrayList<DayRow>();
        int profileChanged = 0;
        int staleFeatures = 0;
        int waiting = 0;
        JsonNode profile = null;
        for (Day x : days) {
            rows.add(row(x, in));
            if (x.ours() != null && x.ours().profileChanged()) {
                profileChanged++;
                profile = x.ours().profile();
            }
            if (x.ours() != null && x.ours().staleFeatures()) staleFeatures++;
            if (WAITS_FOR_EXPORT.equals(x.recoveryGroup()) || WAITS_FOR_EXPORT.equals(x.strainGroup())
                    || WAITS_FOR_EXPORT.equals(x.sleepGroup()) || WAITS_FOR_EXPORT.equals(x.sleepNeedGroup())) waiting++;
        }
        var runs = windowTo == null ? List.<Instant>of() : in.recomputeRuns().stream().sorted().toList();

        return new Report(in.modelVersion(), in.status(), in.activatedAt(), in.retiredAt(), m.protocol(), m.source(), in.frozen(),
                m.windowFrom(), windowTo, in.whoopEnd(), lastScored, phase, verdict(cumulative, m, phase), cumulative, rolling, weeks,
                naive(days, in), new Markers(profileChanged, profile, runs, staleFeatures, waiting), rows);
    }

    private static Day classify(LocalDate d, OurDay o, WhoopDay w, WhoopDay wy, Marks m, boolean syncedNight) {
        String rec;
        if (w == null || w.recovery() == null) rec = NO_WHOOP;
        else if (o == null) rec = NO_SCORE_YET;
        else if (o.recovery() == null) rec = o.recoveryReason() == null ? "UNKNOWN" : o.recoveryReason();
        else rec = COMPARED;

        String strain;
        if (w == null || w.strain() == null) strain = NO_WHOOP;
        else if (o == null) strain = NO_SCORE_YET;
        else if (o.strain() == null) strain = o.strainReason() == null ? "UNKNOWN" : o.strainReason();
        else strain = lowCoverage(o, m) ? COMPARED_LOW_COVERAGE : COMPARED;

        String sleep;
        if (w == null || w.sleepPerf() == null) sleep = NO_WHOOP;
        else if (o == null) sleep = NO_SCORE_YET;
        else if (o.sleepPerf() == null) sleep = o.sleepPerfReason() == null ? "UNKNOWN" : o.sleepPerfReason();
        else sleep = COMPARED;

        String need;
        if (w == null || w.sleepNeedMin() == null) need = NO_WHOOP;
        else if (o == null) need = NO_SCORE_YET;
        else if (o.sleepNeed() == null) need = "UNKNOWN";
        else need = COMPARED;

        if (syncedNight) {
            // ADR 0015 D13: the whole day waits for a Garmin export (coach, Q1 of the PR 3 plan).
            rec = waits(rec);
            strain = waits(strain);
            sleep = waits(sleep);
            need = waits(need);
        }
        return new Day(d, o, w, wy, rec, strain, sleep, need);
    }

    /** Only a day that would be compared waits; a day left out for another reason keeps that reason. */
    private static String waits(String group) {
        return COMPARED.equals(group) || COMPARED_LOW_COVERAGE.equals(group) ? WAITS_FOR_EXPORT : group;
    }

    /** H4: below the frozen all-day HR minutes, strain is low coverage (compared, shown apart, no mark). */
    private static boolean lowCoverage(OurDay o, Marks m) {
        double minutes = o.monitoredMinutes() == null ? 0.0 : o.monitoredMinutes();
        return minutes < m.strainMinMonitoredMinutes();
    }

    private static Figures figures(List<Day> days, LocalDate from, LocalDate to, Input in) {
        double conf = in.marks().confidence();
        var rec = days.stream().filter(x -> COMPARED.equals(x.recoveryGroup())).toList();
        double[] wr = rec.stream().mapToDouble(x -> x.whoop().recovery()).toArray();
        double[] or = rec.stream().mapToDouble(x -> x.ours().recovery()).toArray();
        var confusion = new LinkedHashMap<String, Map<String, Integer>>();
        for (String w : BANDS) {
            var r = new LinkedHashMap<String, Integer>();
            for (String o : BANDS) r.put(o, 0);
            confusion.put(w, r);
        }
        int same = 0, greenRed = 0, lower = 0;
        for (Day x : rec) {
            String wb = band(x.whoop().recovery(), in.greenMin(), in.redMax());
            String ob = band(x.ours().recovery(), in.greenMin(), in.redMax());
            confusion.get(wb).merge(ob, 1, Integer::sum);
            if (wb.equals(ob)) same++;
            if (isGreenRed(wb, ob)) greenRed++;
            if (x.ours().lowerConfidence()) lower++;
        }
        var recovery = new RecoveryFigures(rec.size(), Wilson.rate(same, rec.size(), conf), Wilson.rate(greenRed, rec.size(), conf),
                mae(or, wr), spearman(or, wr), lower, confusion);

        return new Figures(from, to, days.size(), recovery,
                strain(days, x -> COMPARED.equals(x.strainGroup())),
                strain(days, x -> COMPARED_LOW_COVERAGE.equals(x.strainGroup())),
                diff(days, x -> COMPARED.equals(x.sleepGroup()), x -> x.ours().sleepPerf(), x -> x.whoop().sleepPerf().doubleValue()),
                diff(days, x -> COMPARED.equals(x.sleepNeedGroup()), x -> x.ours().sleepNeed(), x -> x.whoop().sleepNeedMin().doubleValue()),
                groups(days));
    }

    private static StrainFigures strain(List<Day> days, Predicate<Day> which) {
        var sel = days.stream().filter(which).toList();
        double[] o = sel.stream().mapToDouble(x -> x.ours().strain()).toArray();
        double[] w = sel.stream().mapToDouble(x -> x.whoop().strain()).toArray();
        return new StrainFigures(sel.size(), mae(o, w), spearman(o, w));
    }

    private static Stat diff(List<Day> days, Predicate<Day> which, java.util.function.ToDoubleFunction<Day> ours,
                             java.util.function.ToDoubleFunction<Day> whoop) {
        var sel = days.stream().filter(which).toList();
        return mae(sel.stream().mapToDouble(ours).toArray(), sel.stream().mapToDouble(whoop).toArray());
    }

    private static Map<String, Map<String, Integer>> groups(List<Day> days) {
        var out = new LinkedHashMap<String, Map<String, Integer>>();
        out.put("recovery", count(days, Day::recoveryGroup));
        out.put("strain", count(days, Day::strainGroup));
        out.put("sleepPerformance", count(days, Day::sleepGroup));
        out.put("sleepNeed", count(days, Day::sleepNeedGroup));
        return out;
    }

    private static Map<String, Integer> count(List<Day> days, java.util.function.Function<Day, String> group) {
        var out = new LinkedHashMap<String, Integer>();
        days.forEach(x -> out.merge(group.apply(x), 1, Integer::sum));
        return out;
    }

    /** H7: yesterday's Whoop recovery and band as today's guess, next to ours on exactly the same days. */
    private static Naive naive(List<Day> days, Input in) {
        double conf = in.marks().confidence();
        var rec = days.stream().filter(x -> COMPARED.equals(x.recoveryGroup())).toList();
        var sel = rec.stream().filter(x -> x.whoopYesterday() != null && x.whoopYesterday().recovery() != null).toList();
        int n = sel.size();
        int oSame = 0, oGr = 0, nSame = 0, nGr = 0;
        double[] w = new double[n], o = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            Day x = sel.get(i);
            String wb = band(x.whoop().recovery(), in.greenMin(), in.redMax());
            String ob = band(x.ours().recovery(), in.greenMin(), in.redMax());
            String yb = band(x.whoopYesterday().recovery(), in.greenMin(), in.redMax());
            if (wb.equals(ob)) oSame++;
            if (isGreenRed(wb, ob)) oGr++;
            if (wb.equals(yb)) nSame++;
            if (isGreenRed(wb, yb)) nGr++;
            w[i] = x.whoop().recovery();
            o[i] = x.ours().recovery();
            y[i] = x.whoopYesterday().recovery();
        }
        return new Naive(n, rec.size() - n,
                new Side(Wilson.rate(oSame, n, conf), Wilson.rate(oGr, n, conf), mae(o, w)),
                new Side(Wilson.rate(nSame, n, conf), Wilson.rate(nGr, n, conf), mae(y, w)));
    }

    /** H6: no mark is judged below the minimum of compared recovery days; then each mark on its measured value. */
    static Verdict verdict(Figures f, Marks m, Phase phase) {
        int n = f.recovery().n();
        boolean enough = n >= m.minRecoveryDays();
        boolean ended = phase != Phase.RUNNING;
        State state = enough ? (ended ? State.FINAL : State.INTERIM)
                : (ended ? State.NOT_ENOUGH_DAYS_BY_RULE : State.NOT_ENOUGH_DAYS_YET);

        var r = f.recovery();
        var s = f.strain();
        var marks = List.of(
                mark("recovery_band_agreement_min", ">=", m.recoveryBandAgreementMin(), r.bandAgreement().value(), n, r.bandAgreement(), enough),
                mark("recovery_green_red_max", "<=", m.recoveryGreenRedMax(), r.greenRed().value(), n, r.greenRed(), enough),
                mark("strain_mae_max", "<=", m.strainMaeMax(), s.mae().value(), s.n(), null, enough),
                mark("strain_spearman_min", ">=", m.strainSpearmanMin(), s.spearman().value(), s.n(), null, enough));
        Overall overall = enough ? (marks.stream().allMatch(x -> Boolean.TRUE.equals(x.met())) ? Overall.PASS : Overall.NOT_MET) : null;
        return new Verdict(state, overall, n, m.minRecoveryDays(), marks);
    }

    /** A mark without a measured value (e.g. no full-coverage strain day) is not met (ADR 0013 D5). */
    private static Mark mark(String key, String cmp, double target, Double value, int n, Wilson.Rate rate, boolean judge) {
        Boolean met = null;
        if (judge) {
            met = value != null && (">=".equals(cmp) ? value >= target : value <= target);
        }
        return new Mark(key, cmp, target, value, n, rate, met);
    }

    private static DayRow row(Day x, Input in) {
        OurDay o = x.ours();
        WhoopDay w = x.whoop();
        WhoopDay y = x.whoopYesterday();
        Integer wr = w == null ? null : w.recovery();
        Integer yr = y == null ? null : y.recovery();
        return new DayRow(x.date(), x.recoveryGroup(),
                o == null ? null : o.recoveryPct(), o == null ? null : o.band(),
                wr, wr == null ? null : band(wr, in.greenMin(), in.redMax()),
                o != null && o.lowerConfidence(),
                x.strainGroup(), o == null ? null : o.strainShown(), w == null ? null : w.strain(),
                COMPARED_LOW_COVERAGE.equals(x.strainGroup()),
                x.sleepGroup(), o == null ? null : o.sleepPerfPct(),
                w == null ? null : w.sleepPerf(),
                yr, yr == null ? null : band(yr, in.greenMin(), in.redMax()));
    }

    // ---------------------------------------------------------------- arithmetic (calibration/metrics.py)

    static final List<String> BANDS = List.of("GREEN", "YELLOW", "RED");

    static String band(double value, double greenMin, double redMax) {
        if (value >= greenMin) return "GREEN";
        if (value <= redMax) return "RED";
        return "YELLOW";
    }

    private static boolean isGreenRed(String whoop, String ours) {
        return ("GREEN".equals(whoop) && "RED".equals(ours)) || ("RED".equals(whoop) && "GREEN".equals(ours));
    }

    static Stat mae(double[] ours, double[] whoop) {
        int n = ours.length;
        if (n == 0) return new Stat(0, null);
        double s = 0;
        for (int i = 0; i < n; i++) s += Math.abs(ours[i] - whoop[i]);
        return new Stat(n, s / n);
    }

    /** Spearman's rank correlation with average ranks for ties (as scipy); null below 3 days or without spread. */
    static Stat spearman(double[] ours, double[] whoop) {
        int n = ours.length;
        if (n < 3 || spread(ours) == 0 || spread(whoop) == 0) return new Stat(n, null);
        return new Stat(n, pearson(ranks(ours), ranks(whoop)));
    }

    private static double spread(double[] x) {
        return Arrays.stream(x).max().orElse(0) - Arrays.stream(x).min().orElse(0);
    }

    static double[] ranks(double[] x) {
        int n = x.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingDouble(i -> x[i]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && x[idx[j + 1]] == x[idx[i]]) j++;
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    private static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = Arrays.stream(a).average().orElse(0), mb = Arrays.stream(b).average().orElse(0);
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < n; i++) {
            sab += (a[i] - ma) * (b[i] - mb);
            saa += (a[i] - ma) * (a[i] - ma);
            sbb += (b[i] - mb) * (b[i] - mb);
        }
        return sab / Math.sqrt(saa * sbb);
    }
}
