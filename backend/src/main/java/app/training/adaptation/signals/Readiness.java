package app.training.adaptation.signals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Readiness today (SPEC.md Module 3; ADR 0021 D9): Module 1's recovery and band, with its 3- and 7-day trend and how
 * long the band has lasted. Pure arithmetic on a gap-free date grid. Overnight HRV exists only from 2025-09-17, so
 * days before the athlete's first HRV day carry no readiness ({@code beforeHrv}).
 * <ul>
 *   <li>trend = today's recovery − the mean recovery of the scored days among the previous N days (N = 3 and 7,
 *       [configurable]); none without a score today or without any scored day in that window;</li>
 *   <li>band streak = consecutive scored days, ending today, in today's band; non-green streak = consecutive scored
 *       days, ending today, in yellow or red. A day without a score ends a streak (unknown is not "still yellow").</li>
 * </ul>
 */
public final class Readiness {
    private Readiness() {}

    public record Input(LocalDate date, Integer recoveryPct, String band) {}

    public record Day(Integer recoveryPct, String band, Double trendShort, int trendShortDays, int trendShortUsed, Double trendLong,
                      int trendLongDays, int trendLongUsed, int bandStreakDays, int nonGreenStreakDays, boolean beforeHrv) {}

    public static List<Day> compute(List<Input> days, int shortDays, int longDays, LocalDate firstHrv) {
        var out = new ArrayList<Day>(days.size());
        for (int i = 0; i < days.size(); i++) {
            var d = days.get(i);
            boolean before = firstHrv == null || d.date().isBefore(firstHrv);
            if (before || d.recoveryPct() == null) {
                out.add(new Day(null, null, null, shortDays, 0, null, longDays, 0, 0, 0, before));
                continue;
            }
            double[] s = mean(days, i, shortDays);
            double[] l = mean(days, i, longDays);
            int streak = 0, nonGreen = 0;
            for (int j = i; j >= 0 && days.get(j).recoveryPct() != null && d.band() != null && d.band().equals(days.get(j).band()); j--) streak++;
            for (int j = i; j >= 0 && days.get(j).recoveryPct() != null && days.get(j).band() != null && !"GREEN".equals(days.get(j).band()); j--)
                nonGreen++;
            out.add(new Day(d.recoveryPct(), d.band(), s[1] == 0 ? null : LoadSignals.round(d.recoveryPct() - s[0], 1), shortDays, (int) s[1],
                    l[1] == 0 ? null : LoadSignals.round(d.recoveryPct() - l[0], 1), longDays, (int) l[1], streak, nonGreen, false));
        }
        return out;
    }

    /** {mean, count} of the scored recoveries among the n days before index i. */
    private static double[] mean(List<Input> days, int i, int n) {
        double sum = 0;
        int count = 0;
        for (int j = Math.max(0, i - n); j < i; j++) {
            Integer r = days.get(j).recoveryPct();
            if (r != null) {
                sum += r;
                count++;
            }
        }
        return new double[] {count == 0 ? 0 : sum / count, count};
    }
}
