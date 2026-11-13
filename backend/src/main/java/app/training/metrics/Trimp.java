package app.training.metrics;

import java.util.List;

/**
 * Heart-rate training load (TRIMP) for one activity. Pure functions: no I/O, so the
 * formulas are unit-tested directly.
 *
 * <ul>
 *   <li>Banister: Σ Δt[min] · HRr · k1 · e^(k2·HRr), HRr = (HR − HRrest) / (HRmax − HRrest), clamped to 0..1.
 *   <li>Edwards: Σ Δt[min] · zone weight (1..5) by %HRmax; below the first zone counts 0.
 * </ul>
 * Each interval uses the HR at its start (left Riemann sum); intervals longer than the
 * max gap are skipped as pauses or dropouts.
 */
public final class Trimp {
    private Trimp() {}

    public static final List<Double> DEFAULT_HRR_ZONE_FLOORS = List.of(0.50, 0.60, 0.70, 0.80, 0.90);
    public static final int ZONES = 6;

    public record Params(int hrMax, int hrRest, double k1, double k2, List<Double> edwardsFloors, int maxGapSeconds,
                         List<Double> hrrZoneFloors) {
        public Params {
            if (hrMax <= hrRest) throw new IllegalArgumentException("HRmax must be above HRrest");
            if (hrrZoneFloors.size() != ZONES - 1) throw new IllegalArgumentException("Need " + (ZONES - 1) + " HRR zone floors");
        }

        public Params(int hrMax, int hrRest, double k1, double k2, List<Double> edwardsFloors, int maxGapSeconds) {
            this(hrMax, hrRest, k1, k2, edwardsFloors, maxGapSeconds, DEFAULT_HRR_ZONE_FLOORS);
        }

        public Params withMaxGap(int seconds) {
            return new Params(hrMax, hrRest, k1, k2, edwardsFloors, seconds, hrrZoneFloors);
        }
    }

    /** One HR sample: seconds since the activity start, and bpm (may be null). */
    public record Sample(long t, Integer hr) {}

    /** zoneMinutes: minutes below zone 1 and in HRR zones 1..5 (index 0..5). */
    public record Result(double banister, double edwards, int samples, long coveredSeconds, double[] zoneMinutes) {}

    public static Result fromStream(List<Sample> samples, Params p) {
        double banister = 0;
        double edwards = 0;
        long covered = 0;
        int used = 0;
        double[] zones = new double[ZONES];
        for (int i = 0; i + 1 < samples.size(); i++) {
            Sample s = samples.get(i);
            long dt = samples.get(i + 1).t() - s.t();
            if (s.hr() == null || s.hr() <= 0 || dt <= 0 || dt > p.maxGapSeconds()) continue;
            double minutes = dt / 60.0;
            banister += minutes * banisterWeight(s.hr(), p);
            edwards += minutes * edwardsWeight(s.hr(), p);
            zones[hrrZone(s.hr(), p)] += minutes;
            covered += dt;
            used++;
        }
        return new Result(banister, edwards, used, covered, zones);
    }

    /** Fallback when only an average HR is known: the whole duration at that HR. */
    public static Result fromAverage(int avgHr, int durationSeconds, Params p) {
        double minutes = durationSeconds / 60.0;
        double[] zones = new double[ZONES];
        zones[hrrZone(avgHr, p)] = minutes;
        return new Result(minutes * banisterWeight(avgHr, p), minutes * edwardsWeight(avgHr, p), 0, durationSeconds, zones);
    }

    static double banisterWeight(int hr, Params p) {
        double hrr = (hr - p.hrRest()) / (double) (p.hrMax() - p.hrRest());
        hrr = Math.max(0, Math.min(1, hrr));
        return hrr * p.k1() * Math.exp(p.k2() * hrr);
    }

    /** 0 below the first floor, else 1..5 by heart-rate reserve. */
    static int hrrZone(int hr, Params p) {
        double hrr = (hr - p.hrRest()) / (double) (p.hrMax() - p.hrRest());
        int zone = 0;
        for (double floor : p.hrrZoneFloors()) if (hrr >= floor) zone++;
        return zone;
    }

    static int edwardsWeight(int hr, Params p) {
        double pct = hr / (double) p.hrMax();
        int zone = 0;
        for (double floor : p.edwardsFloors()) if (pct >= floor) zone++;
        return zone;
    }
}
