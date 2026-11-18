package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SleepDebtTest {
    static List<SleepDebt.Input> nights(Integer need, Integer... slept) {
        var out = new ArrayList<SleepDebt.Input>();
        for (int i = 0; i < slept.length; i++) out.add(new SleepDebt.Input(LocalDate.of(2026, 3, 1).plusDays(i), need, slept[i]));
        return out;
    }

    @Test
    void aLongerNightPaysBackAnEarlierShortOne() {
        var out = SleepDebt.compute(nights(480, 360, 540, 480), 7, true);
        assertThat(out.get(0).debtMin()).isEqualTo(120);
        assertThat(out.get(1).debtMin()).isEqualTo(60);
        assertThat(out.get(2).debtMin()).isEqualTo(60);
        assertThat(out.get(2).nightsOfNeed()).isEqualTo(0.13);
    }

    @Test
    void theDebtNeverGoesBelowZeroSoALongNightDoesNotPrepay() {
        var out = SleepDebt.compute(nights(480, 600, 420), 7, true);
        assertThat(out.get(0).debtMin()).isZero();
        assertThat(out.get(1).debtMin()).isEqualTo(60);
    }

    @Test
    void withoutPaybackOnlyShortfallsAddUp() {
        var out = SleepDebt.compute(nights(480, 360, 540, 420), 7, false);
        assertThat(out.get(2).debtMin()).isEqualTo(180);
    }

    @Test
    void onlyTheWindowCountsAndUnknownNightsAreSkipped() {
        var out = SleepDebt.compute(nights(480, 0, 480, null, 480, 480, 480, 480, 480), 7, true);
        assertThat(out.get(6).debtMin()).as("the 0-hour night is in the window").isEqualTo(480);
        assertThat(out.get(6).knownNights()).isEqualTo(6);
        assertThat(out.get(7).debtMin()).as("it has left the window").isZero();
        assertThat(SleepDebt.compute(nights(null, 400), 7, true).get(0).debtMin()).isNull();
    }

    @Test
    void moreThanOneNightsNeed() {
        var out = SleepDebt.compute(nights(480, 240, 240, 240), 7, true);
        assertThat(out.get(2).debtMin()).isEqualTo(720);
        assertThat(out.get(2).nightsOfNeed()).isEqualTo(1.5);
    }
}
