package app.training.planning;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.SessionTypes.Demand;
import app.training.planning.SessionTypes.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SessionValidatorTest {
    private static final LocalDate D = LocalDate.of(2026, 11, 23);

    private static SessionRequest req(LocalDate date, String type, Demand demand, BigDecimal km, Integer min, BigDecimal strain) {
        return new SessionRequest(null, date, type, demand, "  Tempo  ", " 3 km WU, 20 min @ T, 2 km CD ", km, min, strain, null, "  ");
    }

    @Test
    void fillsInDemandFromTheTypeAndStatusPlannedAndTrimsText() {
        var r = SessionValidator.check(req(D, "threshold", null, new BigDecimal("12.5"), 60, null));
        assertThat(r.ok()).isTrue();
        assertThat(r.session().demand()).isEqualTo(Demand.KEY);
        assertThat(r.session().status()).isEqualTo(Status.PLANNED);
        assertThat(r.session().title()).isEqualTo("Tempo");
        assertThat(r.session().description()).isEqualTo("3 km WU, 20 min @ T, 2 km CD");
        assertThat(r.session().notes()).isNull();
    }

    @Test
    void anExplicitDemandWins() {
        var r = SessionValidator.check(req(D, "long", Demand.SUPPORTING, null, null, null));
        assertThat(r.session().demand()).isEqualTo(Demand.SUPPORTING);
    }

    @Test
    void everyDefaultDemandIsAsDocumented() {
        assertThat(SessionTypes.defaultDemand("easy")).isEqualTo(Demand.EASY);
        assertThat(SessionTypes.defaultDemand("hills")).isEqualTo(Demand.SUPPORTING);
        assertThat(SessionTypes.defaultDemand("vo2max")).isEqualTo(Demand.KEY);
        assertThat(SessionTypes.all()).hasSize(12);
    }

    @Test
    void listsEveryProblemInPlainWords() {
        var r = SessionValidator.check(req(null, "fartlek", null, new BigDecimal("-1"), 2000, new BigDecimal("22")));
        assertThat(r.ok()).isFalse();
        assertThat(r.problems()).containsExactly(
                "Date is missing",
                "Unknown type 'fartlek'",
                "Distance must be between 0 and 500 km",
                "Duration must be between 0 and 1440 minutes",
                "Estimated strain must be between 0 and 21");
    }

    @Test
    void aMissingTypeIsNamed() {
        assertThat(SessionValidator.check(req(D, " ", null, null, null, null)).problems()).containsExactly("Type is missing");
    }
}
