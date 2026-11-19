package app.training.adaptation.signals;

import app.training.adaptation.settings.AppSettings;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Running tolerance in km per week (SPEC.md Module 3; ADR 0021 D11), per day, with its derivation. Pure.
 * <ol>
 *   <li><b>Peak</b>: the highest average weekly running km over any {@code windowWeeks}-week window [4] that lies in the
 *       last {@code lookbackDays} [365] and has no flagged day (a niggle not marked gone, or an injury).</li>
 *   <li><b>Decay for time away</b>: a 7-day block with less than {@code interruptedWeekShare} [0.5] of the peak is an
 *       interrupted week. Only the <i>current break</i> counts: walking back from the day in 7-day blocks (not past the
 *       peak window), the interrupted weeks are counted until {@code returnWeeks} [2] normal weeks in a row are found,
 *       which mark the end of the last break (ADR 0021 amendment, 2026-11-19: easier weeks scattered through steady
 *       training are not time away). Each interrupted week of the current break beyond {@code graceWeeks} [1] takes
 *       {@code decayPerWeek} [10 %] of the peak off, down to 0.</li>
 *   <li><b>Cap</b>: never above the chronic running volume (EWMA of daily running km over the chronic span, × 7) ×
 *       {@code chronicCapFactor} [1.0]. Without a chronic value yet (too few days), no cap is applied and that is said.</li>
 *   <li><b>Override</b>: the coach's newest override from its own day on replaces the computed number (shown beside it).</li>
 * </ol>
 * Days without any record count as 0 km in the sums (no running was recorded) and are skipped by the EWMA.
 */
public final class Tolerance {
    private Tolerance() {}

    public record Settings(int lookbackDays, int windowWeeks, double interruptedWeekShare, double decayPerWeek, int graceWeeks,
                           int returnWeeks, double chronicCapFactor, int chronicSpanDays) {
        public static Settings from(AppSettings s) {
            return new Settings(s.intAt("signals.tolerance.lookbackDays"), s.intAt("signals.tolerance.windowWeeks"),
                    s.doubleAt("signals.tolerance.interruptedWeekShare"), s.doubleAt("signals.tolerance.decayPerWeek"),
                    s.intAt("signals.tolerance.graceWeeks"), s.intAt("signals.tolerance.returnWeeks"),
                    s.doubleAt("signals.tolerance.chronicCapFactor"),
                    s.intAt("signals.load.chronicSpanDays"));
        }
    }

    public record Input(LocalDate date, double runKm, boolean known, boolean flagged) {}

    public record OverrideValue(Double kmPerWeek, String note, LocalDate since) {}

    public record Day(Double kmPerWeek, String source, Double computedKmPerWeek, Double peakKmPerWeek, LocalDate peakFrom, LocalDate peakTo,
                      int cleanWindows, int flaggedWindows, int interruptedWeeks, int decayWeeks, double decayFactor,
                      Double decayedKmPerWeek, Double chronicKmPerWeek, Double capKmPerWeek, boolean capped, OverrideValue override) {}

    /** {@code overrides}: the override in force on each day (null = none), same length as {@code days}. */
    public static List<Day> compute(List<Input> days, Settings s, List<OverrideValue> overrides) {
        int n = days.size();
        int len = 7 * s.windowWeeks();
        double[] km = new double[n + 1];
        int[] flags = new int[n + 1];
        Double[] runSeries = new Double[n];
        for (int i = 0; i < n; i++) {
            km[i + 1] = km[i] + days.get(i).runKm();
            flags[i + 1] = flags[i] + (days.get(i).flagged() ? 1 : 0);
            runSeries[i] = days.get(i).known() ? days.get(i).runKm() : null;
        }
        Double[] chronic = Ewma.of(runSeries, s.chronicSpanDays());

        var out = new ArrayList<Day>(n);
        for (int i = 0; i < n; i++) {
            double peak = -1;
            int peakEnd = -1, clean = 0, flagged = 0;
            for (int e = Math.max(len - 1, i - s.lookbackDays() + len); e <= i; e++) {
                int start = e - len + 1;
                if (flags[e + 1] - flags[start] > 0) {
                    flagged++;
                    continue;
                }
                clean++;
                double weekly = (km[e + 1] - km[start]) / s.windowWeeks();
                if (weekly >= peak) {
                    peak = weekly;
                    peakEnd = e;
                }
            }
            Double chronicWeek = chronic[i] == null ? null : chronic[i] * 7;
            Double cap = chronicWeek == null ? null : chronicWeek * s.chronicCapFactor();
            var override = overrides == null ? null : overrides.get(i);
            if (peakEnd < 0 || peak <= 0) {
                Double ov = override == null ? null : override.kmPerWeek();
                out.add(new Day(r(ov), ov == null ? "none" : "override", null, null, null, null, clean, flagged, 0, 0, 1.0, null, r(chronicWeek),
                        r(cap), false, override));
                continue;
            }
            // The current break: interrupted weeks back from the day, until returnWeeks normal weeks in a row end it.
            int interrupted = 0, normalInRow = 0;
            for (int end = i; end - 6 > peakEnd && normalInRow < s.returnWeeks(); end -= 7) {
                double block = km[end + 1] - km[end - 6];
                if (block < s.interruptedWeekShare() * peak) {
                    interrupted++;
                    normalInRow = 0;
                } else {
                    normalInRow++;
                }
            }
            int decayWeeks = Math.max(0, interrupted - s.graceWeeks());
            double factor = Math.max(0, 1 - s.decayPerWeek() * decayWeeks);
            double decayed = peak * factor;
            boolean capped = cap != null && cap < decayed;
            double computed = capped ? cap : decayed;
            boolean useOverride = override != null && override.kmPerWeek() != null;
            out.add(new Day(r(useOverride ? override.kmPerWeek() : computed), useOverride ? "override" : "computed", r(computed), r(peak),
                    days.get(peakEnd - len + 1).date(), days.get(peakEnd).date(), clean, flagged, interrupted, decayWeeks,
                    LoadSignals.round(factor, 2), r(decayed), r(chronicWeek), r(cap), capped, override));
        }
        return out;
    }

    private static Double r(Double v) {
        return v == null ? null : LoadSignals.round(v, 1);
    }
}
