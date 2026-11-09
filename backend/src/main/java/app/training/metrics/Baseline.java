package app.training.metrics;

import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Personal rolling baseline: mean and SD over the trailing window d−window … d−1. The day
 * itself is excluded, so a bad night cannot dilute its own deviation. Below the minimum
 * number of valid days there is no baseline.
 */
public final class Baseline {
    private Baseline() {}

    public record Stats(int n, double mean, double sd) {
        /** Z-score of x, or null when the spread is zero. */
        public Double z(double x) {
            return sd > 0 ? (x - mean) / sd : null;
        }
    }

    public static Stats of(NavigableMap<LocalDate, Double> series, LocalDate day, int windowDays, int minDays) {
        Map<LocalDate, Double> window = series.subMap(day.minusDays(windowDays), true, day, false);
        int n = 0;
        double sum = 0;
        double sumSq = 0;
        for (Double v : window.values()) {
            if (v == null) continue;
            n++;
            sum += v;
            sumSq += v * v;
        }
        if (n < minDays) return null;
        double mean = sum / n;
        double variance = n > 1 ? Math.max(0, (sumSq - n * mean * mean) / (n - 1)) : 0;
        return new Stats(n, mean, Math.sqrt(variance));
    }
}
