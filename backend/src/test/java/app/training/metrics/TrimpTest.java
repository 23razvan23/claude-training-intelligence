package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrimpTest {
    static final List<Double> FLOORS = List.of(0.50, 0.60, 0.70, 0.80, 0.90);
    // HRmax 190, HRrest 50: HRr = (HR - 50) / 140
    static final Trimp.Params MALE = new Trimp.Params(190, 50, 0.64, 1.92, FLOORS, 10);
    static final Trimp.Params FEMALE = new Trimp.Params(190, 50, 0.86, 1.67, FLOORS, 10);

    static List<Trimp.Sample> constant(int hr, int seconds) {
        var s = new ArrayList<Trimp.Sample>();
        for (int t = 0; t <= seconds; t++) s.add(new Trimp.Sample(t, hr));
        return s;
    }

    @Test
    void banisterGoldenValueAtConstantHr() {
        // 60 min at 155 bpm: HRr = 0.75; 60 * 0.75 * 0.64 * e^(1.92*0.75) = 60 * 0.48 * e^1.44
        double expected = 60 * 0.75 * 0.64 * Math.exp(1.44);
        var r = Trimp.fromStream(constant(155, 3600), MALE);
        assertThat(r.banister()).isCloseTo(expected, within(1e-9));
        assertThat(r.banister()).isCloseTo(121.556, within(0.001));
        assertThat(r.samples()).isEqualTo(3600);
        assertThat(r.coveredSeconds()).isEqualTo(3600);
    }

    @Test
    void femaleConstantsDiffer() {
        double expected = 60 * 0.75 * 0.86 * Math.exp(1.67 * 0.75);
        assertThat(Trimp.fromStream(constant(155, 3600), FEMALE).banister()).isCloseTo(expected, within(1e-9));
    }

    @Test
    void edwardsZones() {
        // 155/190 = 81.6% -> zone 4; 30 min -> 120
        assertThat(Trimp.fromStream(constant(155, 1800), MALE).edwards()).isCloseTo(120, within(1e-9));
        assertThat(Trimp.edwardsWeight(94, MALE)).isZero();     // 49.5%
        assertThat(Trimp.edwardsWeight(95, MALE)).isEqualTo(1); // 50%
        assertThat(Trimp.edwardsWeight(171, MALE)).isEqualTo(5); // 90%
        assertThat(Trimp.edwardsWeight(205, MALE)).isEqualTo(5); // above max stays 5
    }

    @Test
    void hrrIsClampedToZeroAndOne() {
        assertThat(Trimp.banisterWeight(40, MALE)).isZero();
        assertThat(Trimp.banisterWeight(220, MALE)).isCloseTo(0.64 * Math.exp(1.92), within(1e-12));
    }

    @Test
    void gapsAndMissingHrAreNotIntegrated() {
        var s = new ArrayList<Trimp.Sample>();
        for (int t = 0; t < 60; t++) s.add(new Trimp.Sample(t, 155));      // 60 s covered (incl. 59->60)
        for (int t = 120; t <= 180; t++) s.add(new Trimp.Sample(t, 155));  // 60 s gap from 59 -> 120 skipped
        s.add(new Trimp.Sample(181, null));                                // 180->181 counted, 181->182 has no HR
        s.add(new Trimp.Sample(182, 155));
        var r = Trimp.fromStream(s, MALE);
        assertThat(r.coveredSeconds()).isEqualTo(59 + 60 + 1);
        assertThat(r.banister()).isCloseTo(120 / 60.0 * 0.75 * 0.64 * Math.exp(1.44), within(1e-9));
    }

    @Test
    void averageFallbackEqualsConstantStream() {
        var avg = Trimp.fromAverage(155, 3600, MALE);
        var stream = Trimp.fromStream(constant(155, 3600), MALE);
        assertThat(avg.banister()).isCloseTo(stream.banister(), within(1e-9));
        assertThat(avg.edwards()).isCloseTo(stream.edwards(), within(1e-9));
    }

    @Test
    void invalidProfileIsRejected() {
        assertThatThrownBy(() -> new Trimp.Params(50, 50, 0.64, 1.92, FLOORS, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyOrSingleSampleIsZero() {
        assertThat(Trimp.fromStream(List.of(), MALE).banister()).isZero();
        assertThat(Trimp.fromStream(List.of(new Trimp.Sample(0, 150)), MALE).banister()).isZero();
    }
}
