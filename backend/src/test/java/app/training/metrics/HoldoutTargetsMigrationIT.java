package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * V15 freezes holdout targets for a version that was already ACTIVE before activation copied
 * them (m15-20261115 on the coach's machine; coach decision 2026-11-15, ADR 0009). Status and
 * activation times are never touched; other versions keep a null column.
 */
class HoldoutTargetsMigrationIT extends IntegrationTest {
    private static final String TARGETS = """
            {"targets": {"strain_mae_max": 2.0, "strain_spearman_min": 0.8,
                         "recovery_band_agreement_min": 0.7, "recovery_green_red_max": 0.03}}""";
    private static final String V2 = "{\"schema_version\": 2, \"strain\": {\"min_monitored_minutes\": 600}}";

    @Test
    void migrationFreezesTheTargetsOfTheActiveVersionOnly() throws Exception {
        long a = newAthlete("Holdout");
        long active = insert(a, "m-act", "ACTIVE", V2);
        long candidate = insert(a, "m-cand", "CANDIDATE", V2);
        long retired = insert(a, "m-ret", "RETIRED", V2);
        long b = newAthlete("Holdout old contract");
        long activeV1 = insert(b, "m-v1", "ACTIVE", "{}");
        long c = newAthlete("Holdout already frozen");
        long frozen = insert(c, "m-frozen", "ACTIVE", V2);
        jdbc.sql("UPDATE model_version SET holdout_targets = '{\"source\": \"activation\"}' WHERE id = ?").param(frozen).update();
        jdbc.sql("UPDATE model_version SET activated_at = '2026-11-15T19:47:54Z' WHERE id = ?").param(active).update();

        String sql = new String(new ClassPathResource("db/migration/V15__holdout_targets.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.sql(sql).update();

        var t = targets(active);
        assertThat(t.get("strain_mae_max").asDouble()).isEqualTo(2.0);
        assertThat(t.get("strain_spearman_min").asDouble()).isEqualTo(0.8);
        assertThat(t.get("recovery_band_agreement_min").asDouble()).isEqualTo(0.7);
        assertThat(t.get("recovery_green_red_max").asDouble()).isEqualTo(0.03);
        assertThat(t.get("strain_min_monitored_minutes").asInt()).isEqualTo(600);
        assertThat(t.get("min_recovery_days").asInt()).isEqualTo(90);
        assertThat(t.get("confidence").asDouble()).isEqualTo(0.95);
        assertThat(t.get("interval").asText()).isEqualTo("wilson");
        assertThat(t.get("window_from").asText()).isEqualTo("2026-11-09");
        assertThat(t.get("protocol").asText()).isEqualTo("ADR 0009");
        assertThat(t.get("source").asText()).isEqualTo("V15");

        assertThat(targets(candidate)).isNull();
        assertThat(targets(retired)).isNull();
        assertThat(targets(activeV1)).as("a contract the scorer can't read is not frozen").isNull();
        assertThat(targets(frozen).get("source").asText()).as("already frozen: left alone").isEqualTo("activation");
        assertThat(jdbc.sql("SELECT status FROM model_version WHERE id = ?").param(active).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT activated_at::text FROM model_version WHERE id = ?").param(active).query(String.class).single())
                .startsWith("2026-11-15 19:47:54");
    }

    private long insert(long athlete, String version, String status, String coefficients) {
        return jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html, test_to)
                VALUES (?, ?, ?, 'f3', ?::jsonb, ?::jsonb, true, '', '2026-11-08') RETURNING id""")
                .params(athlete, version, status, coefficients, TARGETS).query(Long.class).single();
    }

    private JsonNode targets(long id) throws Exception {
        var raw = jdbc.sql("SELECT holdout_targets::text FROM model_version WHERE id = ?").param(id)
                .query(String.class).optional().orElse(null);
        return raw == null ? null : new ObjectMapper().readTree(raw);
    }
}
