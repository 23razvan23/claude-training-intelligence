package app.training.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V22 (R-13, ADR 0018) applies on a database that already holds Phase 1 data, and leaves that
 * data as it was. Builds a separate database up to V21, fills it with synthetic rows, then
 * migrates to the latest version.
 */
class Phase2SchemaMigrationIT extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void appliesOnTopOfPhase1DataAndKeepsIt() {
        var main = (HikariDataSource) dataSource;
        jdbc.sql("DROP DATABASE IF EXISTS r13_migration").update();
        jdbc.sql("CREATE DATABASE r13_migration").update();
        String url = main.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/r13_migration$1");
        var ds = new DriverManagerDataSource(url, main.getUsername(), main.getPassword());
        var db = JdbcClient.create(ds);

        Flyway.configure().dataSource(ds).target("21").load().migrate();
        assertThat(db.sql("SELECT max(version::int) FROM flyway_schema_history").query(Integer.class).single()).isEqualTo(21);

        long a = db.sql("INSERT INTO athlete (name) VALUES ('Migrated') RETURNING id").query(Long.class).single();
        long batch = db.sql("INSERT INTO import_batch (athlete_id, kind, filename, status) VALUES (?, 'GARMIN_EXPORT', 't', 'SUCCEEDED')"
                + " RETURNING id").param(a).query(Long.class).single();
        db.sql("INSERT INTO daily_wellness (athlete_id, source, source_id, calendar_date, resting_hr, import_batch_id)"
                + " VALUES (?, 'GARMIN', 'w1', '2026-11-10', 48, ?)").params(a, batch).update();
        db.sql("INSERT INTO sleep_session (athlete_id, source, source_id, start_at, end_at, cycle_date, import_batch_id)"
                + " VALUES (?, 'GARMIN', 's1', '2026-11-09T21:00:00Z', '2026-11-10T05:00:00Z', '2026-11-10', ?)").params(a, batch).update();
        long act = db.sql("INSERT INTO activity (athlete_id, source, source_id, sport, start_at, calendar_date, import_batch_id)"
                + " VALUES (?, 'GARMIN', 'a1', 'running', '2026-11-10T07:00:00Z', '2026-11-10', ?) RETURNING id")
                .params(a, batch).query(Long.class).single();
        db.sql("INSERT INTO day_features (athlete_id, cycle_date, feature_version, has_garmin_core, run_km)"
                + " VALUES (?, '2026-11-10', 'f3', TRUE, 10.5)").param(a).update();
        db.sql("INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)"
                + " VALUES (?, 'm-test', 'ACTIVE', 'f3', '{}', '{}', TRUE, '')").param(a).update();
        db.sql("INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct, strain, loads)"
                + " VALUES (?, '2026-11-10', 'm-test', 'f3', 71, 12.3, '{\"workout_load\": 80}')").param(a).update();
        db.sql("INSERT INTO subjective_log (athlete_id, log_date, activity_id, rpe, niggle_location) VALUES (?, '2026-11-10', ?, 6, 'calf')")
                .params(a, act).update();
        // A Phase 2 row too (empty on the coach's machine): the migration must not fail on it.
        db.sql("INSERT INTO planned_session (athlete_id, session_date, session_type, title, is_key) VALUES (?, '2026-11-12', 'long', 'Long', TRUE)")
                .param(a).update();

        Flyway.configure().dataSource(ds).load().migrate();

        assertThat(db.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '22' AND success").query(Long.class).single())
                .isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM daily_wellness WHERE athlete_id = ?").param(a).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM sleep_session_current WHERE athlete_id = ?").param(a).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM activity_with_cycle WHERE athlete_id = ?").param(a).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT run_km::text FROM day_features WHERE athlete_id = ?").param(a).query(String.class).single()).isEqualTo("10.50");
        assertThat(db.sql("SELECT recovery_pct || '/' || strain || '/' || (loads->>'workout_load') FROM derived_day_metrics WHERE athlete_id = ?")
                .param(a).query(String.class).single()).isEqualTo("71/12.3/80");
        var checkIn = db.sql("SELECT rpe, niggle_location, injury, niggle_resolved, scope_key FROM subjective_current WHERE athlete_id = ?")
                .param(a).query().singleRow();
        assertThat(checkIn).containsEntry("rpe", 6).containsEntry("niggle_location", "calf")
                .containsEntry("injury", false).containsEntry("niggle_resolved", false)
                .containsEntry("scope_key", "activity:" + act);
        assertThat(db.sql("SELECT title FROM planned_session WHERE athlete_id = ?").param(a).query(String.class).single()).isEqualTo("Long");

        jdbc.sql("DROP DATABASE r13_migration WITH (FORCE)").update();
    }
}
