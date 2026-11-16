package app.training.metrics.holdout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** ADR 0009 H5: the worked examples of the Wilson interval. */
class WilsonTest {

    private static void expect(int k, int n, double lowPct, double highPct) {
        var r = Wilson.rate(k, n, 0.95);
        assertThat(r.k()).isEqualTo(k);
        assertThat(r.n()).isEqualTo(n);
        assertThat(r.value()).isCloseTo((double) k / n, within(1e-12));
        assertThat(100 * r.low()).as("low of %d/%d", k, n).isCloseTo(lowPct, within(0.05));
        assertThat(100 * r.high()).as("high of %d/%d", k, n).isCloseTo(highPct, within(0.05));
    }

    @Test
    void adrExamples() {
        expect(0, 55, 0.0, 6.5);
        expect(0, 90, 0.0, 4.1);
        expect(3, 90, 1.1, 9.3);
        expect(79, 90, 79.4, 93.0);
    }

    @Test
    void allAndNothingHaveExactBounds() {
        assertThat(Wilson.rate(0, 10, 0.95).low()).isEqualTo(0.0);
        assertThat(Wilson.rate(10, 10, 0.95).high()).isEqualTo(1.0);
    }

    @Test
    void noDaysGiveNoRate() {
        var r = Wilson.rate(0, 0, 0.95);
        assertThat(r.n()).isZero();
        assertThat(r.value()).isNull();
        assertThat(r.low()).isNull();
        assertThat(r.high()).isNull();
    }

    @Test
    void zOfTheConventionalConfidences() {
        assertThat(Wilson.z(0.95)).isCloseTo(1.959964, within(1e-6));
        assertThat(Wilson.z(0.90)).isCloseTo(1.644854, within(1e-6));
        assertThat(Wilson.z(0.99)).isCloseTo(2.575829, within(1e-6));
    }

    @Test
    void refusesImpossibleCounts() {
        assertThatThrownBy(() -> Wilson.rate(5, 4, 0.95)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Wilson.z(1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
