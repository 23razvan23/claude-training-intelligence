package app.training.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** V12 removes Whoop cycles stored before they finished (no cycle end), so the finished version can come in. */
class WhoopOpenCyclesMigrationIT extends IntegrationTest {

    @Test
    void migrationRemovesOpenCyclesAndKeepsFinishedOnes() throws Exception {
        long a = newAthlete("Open cycles");
        jdbc.sql("""
                INSERT INTO whoop_reference (athlete_id, cycle_start, cycle_end, cycle_date, recovery_pct, day_strain)
                VALUES (?, '2026-11-07T21:00:00Z', '2026-11-08T21:00:00Z', '2026-11-08', 60, 12.9),
                       (?, '2026-11-08T21:00:00Z', NULL, '2026-11-09', 40, 3.1)""").params(a, a).update();
        jdbc.sql("""
                INSERT INTO whoop_journal_entry (athlete_id, cycle_start, cycle_date, question, answered_yes)
                VALUES (?, '2026-11-07T21:00:00Z', '2026-11-08', 'Alcohol?', false),
                       (?, '2026-11-08T21:00:00Z', '2026-11-09', 'Alcohol?', true)""").params(a, a).update();

        String sql = new String(new ClassPathResource("db/migration/V12__drop_open_whoop_cycles.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.sql(sql).update();

        assertThat(jdbc.sql("SELECT day_strain FROM whoop_reference WHERE athlete_id = ?").param(a)
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("12.9");
        assertThat(jdbc.sql("SELECT cycle_date::text FROM whoop_journal_entry WHERE athlete_id = ?").param(a)
                .query(String.class).single()).isEqualTo("2026-11-08");
    }
}
