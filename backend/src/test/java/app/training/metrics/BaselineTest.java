package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class BaselineTest {
    static final LocalDate D = LocalDate.of(2026, 3, 31);

    @Test
    void usesTrailingWindowAndExcludesTheDay() {
        var s = new TreeMap<LocalDate, Double>();
        for (int k = 1; k <= 30; k++) s.put(D.minusDays(k), 10.0);
        s.put(D, 1000.0);                 // the day itself: excluded
        s.put(D.minusDays(31), 1000.0);   // outside the window: excluded
        var b = Baseline.of(s, D, 30, 14);
        assertThat(b.n()).isEqualTo(30);
        assertThat(b.mean()).isEqualTo(10.0);
        assertThat(b.sd()).isZero();
        assertThat(b.z(12)).isNull(); // no spread -> no z-score
    }

    @Test
    void requiresMinimumDays() {
        var s = new TreeMap<LocalDate, Double>();
        for (int k = 1; k <= 13; k++) s.put(D.minusDays(k), (double) k);
        assertThat(Baseline.of(s, D, 30, 14)).isNull();
        s.put(D.minusDays(20), 5.0);
        assertThat(Baseline.of(s, D, 30, 14)).isNotNull();
    }

    @Test
    void sampleStandardDeviationAndZ() {
        var s = new TreeMap<LocalDate, Double>();
        double[] v = {2, 4, 4, 4, 5, 5, 7, 9};
        for (int k = 0; k < v.length; k++) s.put(D.minusDays(k + 1), v[k]);
        var b = Baseline.of(s, D, 30, 8);
        assertThat(b.mean()).isEqualTo(5.0);
        assertThat(b.sd()).isCloseTo(Math.sqrt(32.0 / 7), within(1e-12)); // n-1 denominator
        assertThat(b.z(5 + b.sd())).isCloseTo(1.0, within(1e-12));
    }
}
