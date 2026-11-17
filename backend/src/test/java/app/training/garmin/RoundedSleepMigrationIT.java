package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * V21 (ADR 0015 D11): sleep rows written by a Garmin sync carried whole-number breathing rate and
 * SpO2. Removing them makes an exported night count with its export version again and leaves
 * everything else from the sync in place. Synthetic rows.
 */
class RoundedSleepMigrationIT extends IntegrationTest {

    private long batch(long athlete, String kind) {
        return jdbc.sql("INSERT INTO import_batch (athlete_id, kind, filename, status) VALUES (?, ?, 't', 'SUCCEEDED') RETURNING id")
                .params(athlete, kind).query(Long.class).single();
    }

    private void night(long athlete, long batch, String sourceId, String day, String resp, String spo2) {
        jdbc.sql("""
                INSERT INTO sleep_session (athlete_id, source, source_id, start_at, end_at, cycle_date, deep_s, light_s, rem_s,
                    sleep_score, avg_respiration, avg_spo2_pct, import_batch_id)
                VALUES (?, 'GARMIN', ?, (?::date - 1) + time '23:00', ?::date + time '07:00', ?::date, 6000, 12000, 7200, 82, ?::numeric,
                    ?::numeric, ?)""")
                .params(athlete, sourceId, day, day, day, resp, spo2, batch).update();
    }

    @Test
    void removesSyncedSleepRowsSoTheExportedNightCountsAgain() throws Exception {
        long a = newAthlete("Rounded sleep");
        long export = batch(a, "GARMIN_EXPORT");
        long sync = batch(a, "GARMIN_SYNC");
        night(a, export, "2026-11-15", "2026-11-15", "12.4", "96.8");
        night(a, sync, "2026-11-15@" + sync, "2026-11-15", "12.0", "97.0");   // the rounded revision
        night(a, sync, "2026-11-17", "2026-11-17", "12.0", "98.0");           // a night only synced
        jdbc.sql("INSERT INTO daily_wellness (athlete_id, source, source_id, calendar_date, resting_hr, import_batch_id)"
                + " VALUES (?, 'GARMIN', 'uds', '2026-11-17', 47, ?)").params(a, sync).update();
        jdbc.sql("INSERT INTO monitoring_hr (athlete_id, ts, hr, import_batch_id) VALUES (?, '2026-11-17T05:00:00Z', 52, ?)")
                .params(a, sync).update();
        assertThat(jdbc.sql("SELECT avg_respiration FROM sleep_session_current WHERE athlete_id = ? AND cycle_date = '2026-11-15'")
                .param(a).query(BigDecimal.class).single()).isEqualByComparingTo("12.0");

        String sql = new String(new ClassPathResource("db/migration/V21__drop_rounded_synced_sleep.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.sql(sql).update();

        assertThat(jdbc.sql("SELECT avg_respiration FROM sleep_session_current WHERE athlete_id = ? AND cycle_date = '2026-11-15'")
                .param(a).query(BigDecimal.class).single()).isEqualByComparingTo("12.4");
        assertThat(count("SELECT count(*) FROM sleep_session WHERE athlete_id = ?", a)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM daily_wellness WHERE athlete_id = ?", a)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM monitoring_hr WHERE athlete_id = ?", a)).isEqualTo(1);
    }
}
