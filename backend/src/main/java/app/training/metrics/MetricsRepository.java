package app.training.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MetricsRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final MetricsProperties props;

    public MetricsRepository(JdbcClient jdbc, ObjectMapper json, MetricsProperties props) {
        this.jdbc = jdbc;
        this.json = json;
        this.props = props;
    }

    public record FeatureSummary(
            String featureVersion, long days, long withGarminCore, long withWhoop, long paired,
            LocalDate from, LocalDate to, LocalDate pairedFrom, LocalDate pairedTo,
            long withoutBaseline, long withoutLoad, Map<String, Long> activityLoadMethods) {}

    public FeatureSummary summary(long athleteId, String featureVersion) {
        var s = jdbc.sql("""
                SELECT count(*) AS days,
                       count(*) FILTER (WHERE has_garmin_core) AS core,
                       count(*) FILTER (WHERE whoop_recovery IS NOT NULL AND whoop_strain IS NOT NULL) AS whoop,
                       count(*) FILTER (WHERE has_garmin_core AND whoop_recovery IS NOT NULL AND whoop_strain IS NOT NULL) AS paired,
                       min(cycle_date) AS lo, max(cycle_date) AS hi,
                       min(cycle_date) FILTER (WHERE has_garmin_core AND whoop_recovery IS NOT NULL) AS plo,
                       max(cycle_date) FILTER (WHERE has_garmin_core AND whoop_recovery IS NOT NULL) AS phi,
                       count(*) FILTER (WHERE has_garmin_core AND ln_rmssd_z IS NULL) AS no_baseline,
                       count(*) FILTER (WHERE day_load_banister IS NULL) AS no_load
                FROM day_features WHERE athlete_id = ? AND feature_version = ?""")
                .params(athleteId, featureVersion)
                .query((rs, i) -> new Object[] {
                    rs.getLong("days"), rs.getLong("core"), rs.getLong("whoop"), rs.getLong("paired"),
                    rs.getObject("lo", LocalDate.class), rs.getObject("hi", LocalDate.class),
                    rs.getObject("plo", LocalDate.class), rs.getObject("phi", LocalDate.class),
                    rs.getLong("no_baseline"), rs.getLong("no_load")})
                .single();
        var methods = new java.util.LinkedHashMap<String, Long>();
        jdbc.sql("SELECT method, count(*) AS n FROM activity_load WHERE athlete_id = ? GROUP BY method ORDER BY method")
                .param(athleteId)
                .query((rs, i) -> methods.put(rs.getString("method"), rs.getLong("n")))
                .list();
        return new FeatureSummary(featureVersion, (Long) s[0], (Long) s[1], (Long) s[2], (Long) s[3],
                (LocalDate) s[4], (LocalDate) s[5], (LocalDate) s[6], (LocalDate) s[7], (Long) s[8], (Long) s[9], methods);
    }

    public List<ModelVersion> models(long athleteId) {
        return jdbc.sql("SELECT * FROM model_version WHERE athlete_id = ? ORDER BY created_at DESC, id DESC")
                .param(athleteId)
                .query(this::map)
                .list();
    }

    public Optional<ModelVersion> model(long id) {
        return jdbc.sql("SELECT * FROM model_version WHERE id = ?").param(id).query(this::map).optional();
    }

    /**
     * Makes this version the athlete's ACTIVE one, recording when and by whom; any previous
     * ACTIVE version is RETIRED with the same time as its retired_at. now() is the
     * transaction's start time, so both rows carry exactly the same instant.
     */
    public void activate(ModelVersion m, long userId) {
        jdbc.sql("""
                UPDATE model_version SET status = 'RETIRED', retired_at = now()
                WHERE athlete_id = ? AND status = 'ACTIVE' AND id <> ?""")
                .params(m.athleteId(), m.id())
                .update();
        jdbc.sql("UPDATE model_version SET status = 'ACTIVE', activated_at = now(), activated_by = ? WHERE id = ?")
                .params(userId, m.id())
                .update();
    }

    public Optional<String> report(long id) {
        return jdbc.sql("SELECT report_html FROM model_version WHERE id = ?").param(id).query(String.class).optional();
    }

    private ModelVersion map(ResultSet rs, int row) throws SQLException {
        try {
            var coefficients = json.readTree(rs.getString("coefficients"));
            return new ModelVersion(
                    rs.getLong("id"),
                    rs.getLong("athlete_id"),
                    rs.getString("version"),
                    rs.getString("status"),
                    rs.getString("feature_version"),
                    rs.getObject("train_from", LocalDate.class),
                    rs.getObject("train_to", LocalDate.class),
                    rs.getObject("test_from", LocalDate.class),
                    rs.getObject("test_to", LocalDate.class),
                    coefficients,
                    json.readTree(rs.getString("metrics")),
                    rs.getBoolean("passed"),
                    rs.getTimestamp("created_at").toInstant(),
                    instant(rs, "activated_at"),
                    instant(rs, "retired_at"),
                    ModelVersion.contractIssue(rs.getString("feature_version"), coefficients, props.featureVersion()));
        } catch (JsonProcessingException e) {
            throw new SQLException(e);
        }
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
