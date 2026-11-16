package app.training.metrics.holdout;

/**
 * The Wilson score interval for a proportion k of n (ADR 0009 H5). It behaves well near 0% and
 * with about 100 days, where the simple "p ± z·√(p(1−p)/n)" formula gives nonsense (for 0 of
 * 55 it would say 0–0%).
 */
public final class Wilson {
    private Wilson() {}

    /** A rate with its count and interval: k of n, k/n, and the interval's bounds (all 0–1). */
    public record Rate(int k, int n, Double value, Double low, Double high) {}

    /** k of n with its Wilson interval at the given two-sided confidence (e.g. 0.95). n = 0 gives nulls. */
    public static Rate rate(int k, int n, double confidence) {
        if (k < 0 || n < 0 || k > n) {
            throw new IllegalArgumentException("k=" + k + ", n=" + n);
        }
        if (n == 0) {
            return new Rate(0, 0, null, null, null);
        }
        double z = z(confidence);
        double p = (double) k / n;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denom;
        double half = z / denom * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n));
        // Exact bounds at k = 0 and k = n (floating point would otherwise give ±1e-17).
        double low = k == 0 ? 0.0 : Math.max(0.0, centre - half);
        double high = k == n ? 1.0 : Math.min(1.0, centre + half);
        return new Rate(k, n, p, low, high);
    }

    /** The two-sided standard normal quantile: 1.959964 for 0.95. */
    public static double z(double confidence) {
        if (!(confidence > 0 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be between 0 and 1: " + confidence);
        }
        return normalQuantile(1 - (1 - confidence) / 2);
    }

    /** Inverse of the standard normal CDF (Acklam's rational approximation, relative error < 1.2e-9). */
    static double normalQuantile(double p) {
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        final double low = 0.02425;
        if (p < low) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - low) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
