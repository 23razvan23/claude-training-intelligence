package app.training.adaptation.engine;

import app.training.adaptation.engine.Model.Session;
import java.time.LocalDate;
import java.util.Map;

/**
 * Running km over rolling 7-day windows ending on each day of the run: km actually run before today, the larger of
 * km run and km planned today, and the km of PLANNED sessions after today. Sessions without a distance add
 * nothing and are counted, so the reasons can say so. Pure.
 */
final class Volume {
    private Volume() {}

    record Window(LocalDate end, double km) {}

    /** km in the 7 days ending on {@code end}, with {@code plannedKm} overriding the distance of some sessions. */
    static double window(Context c, LocalDate end, Map<Long, Double> plannedKm) {
        double km = 0;
        for (LocalDate d = end.minusDays(6); !d.isAfter(end); d = d.plusDays(1)) {
            double run = d.isAfter(c.today) ? 0 : c.in.runKmByDay().getOrDefault(d, 0.0);
            double planned = 0;
            if (!d.isBefore(c.today)) {
                for (Session x : c.on(d)) {
                    if (x.planned() && x.running()) planned += plannedKm.getOrDefault(x.id(), x.km());
                }
            }
            // Today: a run this morning may not be marked done yet, so the larger of run and planned counts, never both.
            km += d.equals(c.today) ? Math.max(run, planned) : run + planned;
        }
        return km;
    }

    /** The fullest 7-day window ending on a day of the run. */
    static Window max(Context c, Map<Long, Double> plannedKm) {
        Window best = null;
        for (LocalDate d = c.today; !d.isAfter(c.end); d = d.plusDays(1)) {
            double km = window(c, d, plannedKm);
            if (best == null || km > best.km() + 1e-9) best = new Window(d, km);
        }
        return best;
    }

    static long runsWithoutDistance(Context c) {
        return c.changeable().stream().filter(x -> x.running() && x.distanceKm() == null).count();
    }

    static Double limit(Context c) {
        var st = c.todayState();
        if (st == null || st.toleranceKmPerWeek() == null) return null;
        return st.toleranceKmPerWeek() * (1 + c.s.toleranceMargin());
    }

    static Double runningAcwr(Context c) {
        return c.in.yesterdayState() == null ? null : c.in.yesterdayState().runningAcwr();
    }
}
