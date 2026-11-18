package app.training.adaptation.signals;

/**
 * Exponentially weighted moving average of a daily series with unknown days, exactly as the Trends screen computes it
 * (ADR 0016 D3, {@code frontend/src/trends.ts ewma}), so the engine and the screen show the same acute and chronic load.
 * <ul>
 *   <li>null = unknown: skipped, never counted as 0 (a recorded rest day is a real 0);</li>
 *   <li>with α = 2/(span+1), a known day {@code g} days after the previous known one moves the average
 *       1 − (1 − α)^g of the way to the new value (for g = 1 that is α);</li>
 *   <li>seeded with the first known value, and null until {@code span} known days have been seen.</li>
 * </ul>
 * The span is [configurable] in the settings store (7 and 28 days by default).
 */
public final class Ewma {
    private Ewma() {}

    public static Double[] of(Double[] series, int span) {
        if (span < 1) throw new IllegalArgumentException("span must be at least 1");
        double alpha = 2.0 / (span + 1);
        Double[] out = new Double[series.length];
        Double avg = null;
        int last = -1;
        int known = 0;
        for (int i = 0; i < series.length; i++) {
            Double x = series[i];
            if (x == null) continue;
            if (avg == null) avg = x;
            else avg = avg + (1 - Math.pow(1 - alpha, i - last)) * (x - avg);
            last = i;
            known++;
            out[i] = known >= span ? avg : null;
        }
        return out;
    }

    /** Acute ÷ chronic where both exist and chronic is above zero. One signal among several, never a gate. */
    public static Double ratio(Double acute, Double chronic) {
        return acute != null && chronic != null && chronic > 0 ? acute / chronic : null;
    }
}
