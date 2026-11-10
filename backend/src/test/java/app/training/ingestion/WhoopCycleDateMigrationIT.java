package app.training.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** V7 corrects Whoop cycle dates that were stored as the sleep-onset date. */
class WhoopCycleDateMigrationIT extends IntegrationTest {

    @Test
    void migrationMovesCyclesToTheWakeDay() throws Exception {
        long a = newAthlete("Migrated");
        // As the old importer stored them: cycle_date = date of the (evening) cycle start.
        jdbc.sql("""
                INSERT INTO whoop_reference (athlete_id, cycle_start, cycle_date, recovery_pct, day_strain)
                VALUES (?, '2026-10-20T20:10:00Z', '2026-10-20', 60, 12.9),
                       (?, '2026-10-21T21:30:00Z', '2026-10-21', 40, 3.8)""").params(a, a).update();
        // A matching Whoop sleep for the first cycle (wake date 2026-10-21); none for the second.
        jdbc.sql("""
                INSERT INTO sleep_session (athlete_id, source, source_id, start_at, end_at, cycle_date)
                VALUES (?, 'WHOOP', 's1', '2026-10-20T20:10:00Z', '2026-10-21T04:00:00Z', '2026-10-21')""").param(a).update();
        jdbc.sql("""
                INSERT INTO whoop_journal_entry (athlete_id, cycle_start, cycle_date, question, answered_yes)
                VALUES (?, '2026-10-20T20:10:00Z', '2026-10-20', 'Alcohol?', false)""").param(a).update();
        long model = jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, 'm-old', 'f1', '{}', '{}', false, '') RETURNING id""").param(a).query(Long.class).single();

        String sql = new String(new ClassPathResource("db/migration/V7__whoop_cycle_date_fix.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.sql(sql).update();

        var dates = jdbc.sql("SELECT cycle_date FROM whoop_reference WHERE athlete_id = ? ORDER BY cycle_start")
                .param(a).query(LocalDate.class).list();
        // First: from the matching sleep's wake date. Second: start (00:30 local Bucharest) + 12 h.
        assertThat(dates).containsExactly(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22));
        assertThat(jdbc.sql("SELECT cycle_date FROM whoop_journal_entry WHERE athlete_id = ?").param(a).query(LocalDate.class).single())
                .isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(jdbc.sql("SELECT status FROM model_version WHERE id = ?").param(model).query(String.class).single()).isEqualTo("RETIRED");
    }
}
