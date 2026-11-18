package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The same cases as frontend/src/trends.test.ts, so the engine and the Trends screen compute one EWMA (ADR 0016 D3). */
class EwmaTest {
    @Test
    void matchesAHandCalculation() {
        // span 3, α = 0.5: seed 10; then 0 → 5; then 0 → 2.5; then 20 → 11.25
        Double[] out = Ewma.of(new Double[] {10.0, 0.0, 0.0, 20.0}, 3);
        assertThat(out[0]).isNull();
        assertThat(out[1]).isNull();
        assertThat(out[2]).isCloseTo(2.5, within(1e-10));
        assertThat(out[3]).isCloseTo(11.25, within(1e-10));
    }

    @Test
    void aRestDayPullsTheAverageDownAnUnknownDayDoesNot() {
        Double[] withRest = Ewma.of(new Double[] {10.0, 10.0, 10.0, 0.0, 10.0}, 3);
        Double[] withUnknown = Ewma.of(new Double[] {10.0, 10.0, 10.0, null, 10.0}, 3);
        assertThat(withRest[4]).isLessThan(10);
        assertThat(withUnknown[4]).isCloseTo(10, within(1e-10));
        assertThat(withUnknown[3]).isNull();
    }

    @Test
    void fadesByTheRealDaysThatPassedAcrossAnUnknownStretch() {
        // known 10 on days 0..2, unknown on 3 and 4, then 0 on day 5 (g = 3): w = 1 - 0.5^3 = 0.875
        Double[] out = Ewma.of(new Double[] {10.0, 10.0, 10.0, null, null, 0.0}, 3);
        assertThat(out[5]).isCloseTo(10 * (1 - 0.875), within(1e-10));
    }

    @Test
    void isHiddenUntilSpanKnownDaysExist() {
        Double[] series = new Double[30];
        Arrays.fill(series, 5.0);
        Double[] out = Ewma.of(series, 28);
        for (int i = 0; i < 27; i++) assertThat(out[i]).isNull();
        assertThat(out[27]).isCloseTo(5, within(1e-10));
    }

    @Test
    void ratioIsBlankUnlessBothExistAndChronicIsAboveZero() {
        assertThat(Ewma.ratio(10.0, 5.0)).isEqualTo(2.0);
        assertThat(Ewma.ratio(null, 5.0)).isNull();
        assertThat(Ewma.ratio(5.0, null)).isNull();
        assertThat(Ewma.ratio(5.0, 0.0)).isNull();
    }
}
