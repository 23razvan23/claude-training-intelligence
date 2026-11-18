package app.training.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.training.IntegrationTest;
import app.training.planning.PlanModels.PlanRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** All or nothing: a failure while saving the third row leaves no session, no history and an unchanged plan. */
class PlanImportRollbackIT extends IntegrationTest {
    @Autowired PlanImportService imports;
    @MockitoSpyBean PlanService plans;

    @Test
    void aFailureHalfWayRollsEveryRowBack() {
        long a = newAthlete("Rollback");
        long coach = jdbc.sql("INSERT INTO app_user (username, password_hash, role) VALUES (?, 'x', 'COACH') RETURNING id")
                .param("u-rollback-" + System.nanoTime()).query(Long.class).single();
        plans.createPlan(a, new PlanRequest("Block", LocalDate.of(2030, 11, 25), LocalDate.of(2030, 11, 26), null), "MANUAL");

        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (calls.incrementAndGet() == 3) throw new IllegalStateException("database went away");
            return inv.callRealMethod();
        }).when(plans).createSession(Mockito.anyLong(), Mockito.any(), Mockito.anyLong());

        byte[] file = (PlanImportIT.HEADER + "25/11/2030,easy,,,,,,\n27/11/2030,easy,,,,,,\n28/11/2030,easy,,,,,,\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> imports.commit(a, file, coach)).hasMessageContaining("database went away");

        assertThat(calls.get()).isEqualTo(3); // the first two rows really were saved before the failure ...
        assertThat(count("SELECT count(*) FROM planned_session WHERE athlete_id = ?", a)).isZero(); // ... and are gone
        assertThat(count("SELECT count(*) FROM planned_session_revision WHERE athlete_id = ?", a)).isZero();
        assertThat(count("SELECT count(*) FROM plan WHERE athlete_id = ? AND end_date = DATE '2030-11-26'", a)).isEqualTo(1);
    }
}
