package app.training.adaptation.signals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Load signals per day (stage 2-2, SPEC.md Module 3 "Signals", ADR 0021). Pure arithmetic on a gap-free date grid:
 * <ul>
 *   <li><b>Acute and chronic load</b>: EWMA ({@link Ewma}) of the daily load in TRIMP units (ADR 0018 D9), total and
 *       running-only, with their ratio (ACWR). One signal among several, never a gate (SPEC.md).</li>
 *   <li><b>Foster monotony and strain</b> over the last {@code fosterWindowDays} days including the day: monotony =
 *       mean daily load ÷ its standard deviation, strain = the window's load × monotony (Foster 1998). Population
 *       standard deviation over the recorded days; none when fewer than {@code fosterMinKnownDays} days are recorded
 *       or all recorded days have the same load (the ratio is then undefined).</li>
 *   <li><b>Running volume</b>: running km and minutes over the last short and long window including the day.</li>
 * </ul>
 * A day's load signals include that day's own load. A day without any record (unknown) has no load value and no
 * EWMA, as on the Trends screen; it adds nothing to the running sums.
 */
public final class LoadSignals {
    private LoadSignals() {}

    /** One day's inputs. {@code totalLoad} null = nothing recorded that day (unknown, not rest). */
    public record DayInput(LocalDate date, Double totalLoad, Double runLoad, double runKm, double runMinutes) {}

    public record RunLoad(Double daily, Double acute, Double chronic, Double acwr) {}

    public record Load(String method, Double daily, Double acute, Double chronic, Double acwr, RunLoad running) {}

    public record Foster(int windowDays, int knownDays, Double weekLoad, Double mean, Double sd, Double monotony, Double strain) {}

    public record Running(int shortWindowDays, double shortKm, double shortMinutes, int longWindowDays, double longKm,
                          double longMinutes, double longWeeklyAvgKm) {}

    public record Day(LocalDate date, Load load, Foster foster, Running running) {}

    public static List<Day> compute(List<DayInput> days, SignalSettings s) {
        int n = days.size();
        for (int i = 1; i < n; i++) {
            if (!days.get(i).date().equals(days.get(i - 1).date().plusDays(1)))
                throw new IllegalArgumentException("Days must be consecutive: " + days.get(i - 1).date() + " then " + days.get(i).date());
        }
        Double[] total = new Double[n];
        Double[] run = new Double[n];
        for (int i = 0; i < n; i++) {
            total[i] = days.get(i).totalLoad();
            run[i] = total[i] == null ? null : days.get(i).runLoad() == null ? 0.0 : days.get(i).runLoad();
        }
        Double[] acute = Ewma.of(total, s.acuteSpanDays());
        Double[] chronic = Ewma.of(total, s.chronicSpanDays());
        Double[] runAcute = Ewma.of(run, s.acuteSpanDays());
        Double[] runChronic = Ewma.of(run, s.chronicSpanDays());

        var out = new ArrayList<Day>(n);
        for (int i = 0; i < n; i++) {
            var load = new Load(s.dailyLoad(), r1(total[i]), r1(acute[i]), r1(chronic[i]), r3(Ewma.ratio(acute[i], chronic[i])),
                    new RunLoad(r1(run[i]), r1(runAcute[i]), r1(runChronic[i]), r3(Ewma.ratio(runAcute[i], runChronic[i]))));
            out.add(new Day(days.get(i).date(), load, foster(total, i, s), running(days, i, s)));
        }
        return out;
    }

    static Foster foster(Double[] total, int i, SignalSettings s) {
        int from = Math.max(0, i - s.fosterWindowDays() + 1);
        int known = 0;
        double sum = 0;
        for (int j = from; j <= i; j++) {
            if (total[j] != null) {
                known++;
                sum += total[j];
            }
        }
        if (known == 0) return new Foster(s.fosterWindowDays(), 0, null, null, null, null, null);
        double mean = sum / known;
        double sq = 0;
        for (int j = from; j <= i; j++) if (total[j] != null) sq += (total[j] - mean) * (total[j] - mean);
        double sd = Math.sqrt(sq / known);
        Double monotony = known >= s.fosterMinKnownDays() && sd > 1e-9 ? mean / sd : null;
        Double strain = monotony == null ? null : sum * monotony;
        return new Foster(s.fosterWindowDays(), known, r1(sum), r1(mean), r1(sd), r3(monotony), r1(strain));
    }

    static Running running(List<DayInput> days, int i, SignalSettings s) {
        double shortKm = 0, shortMin = 0, longKm = 0, longMin = 0;
        for (int j = Math.max(0, i - s.runningShortDays() + 1); j <= i; j++) {
            shortKm += days.get(j).runKm();
            shortMin += days.get(j).runMinutes();
        }
        for (int j = Math.max(0, i - s.runningLongDays() + 1); j <= i; j++) {
            longKm += days.get(j).runKm();
            longMin += days.get(j).runMinutes();
        }
        return new Running(s.runningShortDays(), round(shortKm, 2), round(shortMin, 1), s.runningLongDays(), round(longKm, 2),
                round(longMin, 1), round(longKm * 7.0 / s.runningLongDays(), 2));
    }

    static Double r1(Double v) {
        return v == null ? null : round(v, 1);
    }

    static Double r3(Double v) {
        return v == null ? null : round(v, 3);
    }

    static double round(double v, int places) {
        return java.math.BigDecimal.valueOf(v).setScale(places, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
