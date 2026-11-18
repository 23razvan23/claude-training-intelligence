package app.training.adaptation.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.training.common.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CheckinValidatorTest {
    static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    static CheckinRequest of(Integer rpe, Integer soreness, String loc, Integer sev, boolean resolved, boolean injury,
                             boolean illness, String note) {
        return new CheckinRequest(null, null, null, rpe, soreness, loc, sev, resolved, injury, illness, note);
    }

    static CheckinRequest rpe(int v) {
        return of(v, null, null, null, false, false, false, null);
    }

    static CheckinRequest check(CheckinRequest r) {
        return CheckinValidator.validate(r, TODAY, TODAY, 7);
    }

    @Test
    void aSimpleCheckInPasses() {
        assertThat(check(of(6, 2, null, null, false, false, false, "  felt heavy  ")).note()).isEqualTo("felt heavy");
    }

    @Test
    void rpeIsOneToTen() {
        check(rpe(1));
        check(rpe(10));
        assertThatThrownBy(() -> check(rpe(0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> check(rpe(11))).isInstanceOf(ApiException.class);
    }

    @Test
    void sorenessAndSeverityAreOneToFive() {
        assertThatThrownBy(() -> check(of(null, 6, null, null, false, false, false, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> check(of(null, 0, null, null, false, false, false, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> check(of(null, null, "knee", 6, false, false, false, null))).isInstanceOf(ApiException.class);
    }

    @Test
    void aNiggleNeedsALocationFromTheList() {
        check(of(null, null, "knee", 3, false, false, false, null));
        assertThatThrownBy(() -> check(of(null, null, null, 3, false, false, false, null))).hasMessageContaining("location");
        assertThatThrownBy(() -> check(of(null, null, null, null, true, false, false, null))).hasMessageContaining("location");
        assertThatThrownBy(() -> check(of(null, null, "elbow", 3, false, false, false, null))).hasMessageContaining("location");
    }

    @Test
    void aResolvedNiggleHasNoSeverity() {
        check(of(null, null, "calf", null, true, false, false, null));
        assertThatThrownBy(() -> check(of(null, null, "calf", 2, true, false, false, null))).isInstanceOf(ApiException.class);
    }

    @Test
    void anEmptyCheckInIsRefusedButAFlagAloneCounts() {
        assertThatThrownBy(() -> check(of(null, null, null, null, false, false, false, "   "))).hasMessageContaining("at least one");
        check(of(null, null, null, null, false, true, false, null));
        check(of(null, null, null, null, false, false, true, null));
    }

    @Test
    void theNoteIsLimited() {
        assertThatThrownBy(() -> check(of(null, null, null, null, false, false, false, "x".repeat(501))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aSessionAndAnActivityTogetherAreRefused() {
        var both = new CheckinRequest(null, 1L, 2L, 5, null, null, null, false, false, false, null);
        assertThatThrownBy(() -> check(both)).hasMessageContaining("not both");
    }

    @Test
    void theDayMustBeNeitherInTheFutureNorTooOld() {
        var r = rpe(5);
        CheckinValidator.validate(r, TODAY, TODAY, 7);
        CheckinValidator.validate(r, TODAY.minusDays(7), TODAY, 7);
        assertThatThrownBy(() -> CheckinValidator.validate(r, TODAY.plusDays(1), TODAY, 7)).hasMessageContaining("future");
        assertThatThrownBy(() -> CheckinValidator.validate(r, TODAY.minusDays(8), TODAY, 7)).hasMessageContaining("at most 7");
    }
}
