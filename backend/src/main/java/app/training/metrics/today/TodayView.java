package app.training.metrics.today;

import app.training.metrics.ScoringContract;
import app.training.metrics.ScoringRepository.StoredDay;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * What the Today screen shows (stage 1b-2a, ADR 0014), built from stored daily scores only. Pure
 * code, no database: it never computes a score, it only picks and explains stored ones.
 *
 * <p>The one rule that matters most: {@link Today#day()} is only ever the row whose date is
 * today in the athlete's timezone. An older row can only appear as {@link Today#lastScored()},
 * which always carries its own date (ADR 0008 C2).
 */
public final class TodayView {
    private TodayView() {}

    public enum State {
        /** Today has a row with a recovery score. */
        FRESH,
        /** Today has a row, but no recovery; the reasons say why (ADR 0008 C1). */
        MISSING,
        /** No row for today: the newest data is older (ADR 0008 C2). Normal while data comes from exports. */
        STALE,
        /** The ACTIVE model has no scored days for this athlete yet. */
        NO_DATA,
        /** The athlete has no ACTIVE model. */
        NO_MODEL
    }

    /**
     * @param today today's date in the athlete's timezone
     * @param day today's scores; null unless the state is FRESH or MISSING
     * @param lastScored the newest scored day before today; only set when the state is STALE
     */
    public record Today(LocalDate today, String timezone, State state, String modelVersion, LocalDate lastDataDate,
                        Day day, Day lastScored) {}

    public record Reason(String reason, String detail) {}

    /**
     * A recovery input and its points, with what the screen needs to say it in plain words.
     *
     * @param value the model input as stored (a deviation from the 30-day baseline for HRV z, resting HR and breathing)
     * @param measured HRV only: that night's RMSSD in ms
     * @param baseline HRV only: the 30-day baseline RMSSD in ms (geometric mean, the same window as the features)
     * @param percentVsBaseline HRV only: measured vs baseline, in percent
     * @param typical the training-window average of the input (sign·center), e.g. the usual yesterday's strain
     */
    public record Contribution(String feature, Double value, Double points, Double measured, Double baseline,
                               Double percentVsBaseline, Double typical) {}

    /** A personal strain range for the day's recovery band; never a stored score. */
    public record StrainTarget(double low, double high, String band, int basisDays, int windowDays,
                               double lowPercentile, double highPercentile) {}

    public record Day(LocalDate date, Integer recoveryPct, String band, boolean recoveryLowerConfidence, List<Reason> recoveryReasons,
                      BigDecimal strain, boolean strainLowCoverage, String strainReason, StrainTarget strainTarget,
                      Integer sleepPerformancePct, Integer sleepNeedMin, String sleepReason,
                      List<Contribution> contributions, boolean needsRecompute, boolean drift, boolean profileChanged,
                      boolean liveBreathing) {

        /**
         * The same day, marked as scored with Garmin's live (approximate) breathing rate: the night came
         * from a sync, not an export (ADR 0015 D12). Display only; the score is what it is.
         */
        public Day withLiveBreathing(boolean live) {
            return new Day(date, recoveryPct, band, recoveryLowerConfidence, recoveryReasons, strain, strainLowCoverage, strainReason,
                    strainTarget, sleepPerformancePct, sleepNeedMin, sleepReason, contributions, needsRecompute, drift, profileChanged,
                    live);
        }
    }

    /** No ACTIVE model, or no scored days yet. */
    public static Today empty(LocalDate today, String timezone, String modelVersion) {
        return new Today(today, timezone, modelVersion == null ? State.NO_MODEL : State.NO_DATA, modelVersion, null, null, null);
    }

    /**
     * @param rows the ACTIVE model's stored days, oldest first, ending with the newest day on or before
     *     today, and reaching back at least {@code strainTargetWindowDays} before it
     * @param lnRmssd stored ln(RMSSD) by date (day_features), covering the HRV baseline window
     * @param rmssd stored RMSSD in ms by date
     * @param contract the ACTIVE model's coefficients, or null when they can't be read
     */
    public static Today build(LocalDate today, String timezone, String modelVersion, List<StoredDay> rows,
                              NavigableMap<LocalDate, Double> lnRmssd, Map<LocalDate, Double> rmssd, ScoringContract contract,
                              TodayProperties props, int baselineWindowDays, int baselineMinDays) {
        var upToToday = rows.stream().filter(r -> !r.date().isAfter(today)).toList();
        if (upToToday.isEmpty()) return empty(today, timezone, modelVersion);
        StoredDay newest = upToToday.get(upToToday.size() - 1);
        Day shown = day(newest, upToToday, lnRmssd, rmssd, contract, props, baselineWindowDays, baselineMinDays);
        if (newest.date().equals(today)) {
            State state = newest.recoveryPct() != null ? State.FRESH : State.MISSING;
            return new Today(today, timezone, state, modelVersion, newest.date(), shown, null);
        }
        return new Today(today, timezone, State.STALE, modelVersion, newest.date(), null, shown);
    }

    static Day day(StoredDay r, List<StoredDay> rows, NavigableMap<LocalDate, Double> lnRmssd, Map<LocalDate, Double> rmssd,
                   ScoringContract contract, TodayProperties props, int baselineWindowDays, int baselineMinDays) {
        JsonNode q = r.dataQuality();
        var reasons = new ArrayList<Reason>();
        for (JsonNode n : q.path("recovery_reasons")) {
            reasons.add(new Reason(n.path("reason").asText(), n.hasNonNull("detail") ? n.get("detail").asText() : null));
        }
        boolean lowCoverage = q.path("low_coverage").asBoolean(false);
        StrainTarget target = r.recoveryBand() == null ? null : strainTarget(r.date(), r.recoveryBand(), rows, props);
        return new Day(r.date(), r.recoveryPct(), r.recoveryBand(), q.path("recovery_lower_confidence").asBoolean(false), reasons,
                r.strain(), lowCoverage, text(q, "strain_reason"), target,
                r.sleepPerformancePct(), r.sleepNeedMin(), text(q, "sleep_performance_reason"),
                r.recoveryPct() == null ? List.of() : contributions(r, lnRmssd, rmssd, contract, baselineWindowDays, baselineMinDays),
                q.path("stale").asBoolean(false), q.path("drift").size() > 0, q.path("profile_changed").asBoolean(false), false);
    }

    private static String text(JsonNode q, String key) {
        return q.hasNonNull(key) ? q.get(key).asText() : null;
    }

    /** The recovery inputs, largest effect first. */
    static List<Contribution> contributions(StoredDay r, NavigableMap<LocalDate, Double> lnRmssd, Map<LocalDate, Double> rmssd,
                                            ScoringContract contract, int baselineWindowDays, int baselineMinDays) {
        var out = new ArrayList<Contribution>();
        var terms = r.contributors().path("terms");
        terms.fieldNames().forEachRemaining(feature -> {
            JsonNode t = terms.get(feature);
            Double value = num(t.get("value"));
            Double points = num(t.get("points"));
            Double typical = null;
            if (contract != null) {
                for (var term : contract.recovery().terms()) {
                    if (term.feature().equals(feature)) typical = term.sign() * term.center();
                }
            }
            Double measured = null;
            Double baseline = null;
            Double percent = null;
            if (feature.equals("ln_rmssd_z")) {
                Double ln = lnRmssd.get(r.date());
                var base = app.training.metrics.Baseline.of(lnRmssd, r.date(), baselineWindowDays, baselineMinDays);
                if (ln != null && base != null) {
                    measured = rmssd.getOrDefault(r.date(), Math.exp(ln));
                    baseline = Math.exp(base.mean());
                    percent = (Math.exp(ln - base.mean()) - 1) * 100;
                }
            }
            out.add(new Contribution(feature, value, points, measured, baseline, percent, typical));
        });
        out.sort(Comparator.comparingDouble((Contribution c) -> c.points() == null ? 0 : -Math.abs(c.points())));
        return out;
    }

    private static Double num(JsonNode n) {
        return n == null || n.isNull() ? null : n.asDouble();
    }

    /**
     * The personal strain range for a day with this recovery band [configurable, ADR 0014]: percentiles
     * of the athlete's own strain on the full-coverage days of the window before the day. Null with
     * too few such days.
     */
    static StrainTarget strainTarget(LocalDate date, String band, List<StoredDay> rows, TodayProperties props) {
        var pcts = props.percentiles(band);
        if (pcts == null) return null;
        LocalDate from = date.minusDays(props.strainTargetWindowDays());
        var strains = rows.stream()
                .filter(x -> !x.date().isBefore(from) && x.date().isBefore(date))
                .filter(x -> x.strain() != null && !x.dataQuality().path("low_coverage").asBoolean(false))
                .map(x -> x.strain().doubleValue())
                .sorted()
                .toList();
        if (strains.size() < props.strainTargetMinDays()) return null;
        double lowP = pcts.get(0);
        double highP = pcts.get(1);
        double low = lowP <= 0 ? 0 : percentile(strains, lowP);
        return new StrainTarget(round1(low), round1(percentile(strains, highP)), band, strains.size(),
                props.strainTargetWindowDays(), lowP, highP);
    }

    /** Linear interpolation between closest ranks (the common "type 7" definition, as numpy's default). */
    static double percentile(List<Double> sorted, double p) {
        double h = (sorted.size() - 1) * p / 100.0;
        int lo = (int) Math.floor(h);
        int hi = Math.min(lo + 1, sorted.size() - 1);
        return sorted.get(lo) + (h - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
