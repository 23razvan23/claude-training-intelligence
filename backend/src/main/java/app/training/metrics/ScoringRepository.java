package app.training.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads day_features for scoring and writes derived_day_metrics. */
@Repository
public class ScoringRepository {
    /** Every day_features column a contract may read (strain inputs, sleep, recovery terms). */
    static final List<String> INPUT_COLUMNS = Stream.concat(
            Stream.of("ln_rmssd_z", "rhr_dev", "resp_dev", "sleep_minutes", "sleep_spo2", "body_battery_high",
                    "day_load_banister", "day_load_edwards", "background_load", "monitored_minutes"),
            ScoringContract.ZONE_COLUMNS.stream()).toList();

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ObjectMapper json;

    public ScoringRepository(JdbcClient jdbc, JdbcTemplate template, ObjectMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    /** A day_features row as the scorer reads it, plus when it was computed (for the stale flag). */
    public record FeatureRow(DailyScorer.DayInput input, Instant computedAt) {}

    public List<FeatureRow> features(long athleteId, String featureVersion) {
        return jdbc.sql("SELECT cycle_date, has_garmin_core, computed_at, " + String.join(", ", INPUT_COLUMNS)
                        + " FROM day_features WHERE athlete_id = ? AND feature_version = ? ORDER BY cycle_date")
                .params(athleteId, featureVersion)
                .query(this::featureRow)
                .list();
    }

    private FeatureRow featureRow(ResultSet rs, int i) throws SQLException {
        Map<String, Double> values = new HashMap<>();
        for (String c : INPUT_COLUMNS) {
            Object o = rs.getObject(c);
            values.put(c, o == null ? null : ((Number) o).doubleValue());
        }
        return new FeatureRow(
                new DailyScorer.DayInput(rs.getObject("cycle_date", LocalDate.class), rs.getBoolean("has_garmin_core"), values),
                rs.getTimestamp("computed_at").toInstant());
    }

    /** When the athlete's newest successful import finished; features computed before it may be stale. */
    public Optional<Instant> lastImportFinished(long athleteId) {
        return jdbc.sql("SELECT max(finished_at) FROM import_batch WHERE athlete_id = ? AND status = 'SUCCEEDED'")
                .param(athleteId)
                .query((rs, i) -> rs.getTimestamp(1))
                .optional()
                .map(java.sql.Timestamp::toInstant);
    }

    /**
     * Removes this model version's rows for the athlete, for the given dates (both null: all of
     * them). Rows of other model versions are kept as history.
     */
    public int delete(long athleteId, String modelVersion, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return jdbc.sql("DELETE FROM derived_day_metrics WHERE athlete_id = ? AND model_version = ?")
                    .params(athleteId, modelVersion).update();
        }
        return jdbc.sql("""
                DELETE FROM derived_day_metrics WHERE athlete_id = ? AND model_version = ?
                  AND cycle_date >= coalesce(?::date, '-infinity'::date) AND cycle_date <= coalesce(?::date, 'infinity'::date)""")
                .params(athleteId, modelVersion, from, to).update();
    }

    public record Row(
            LocalDate date, Integer recoveryPct, String band, java.math.BigDecimal strain, Integer sleepPerf, Integer sleepNeed,
            Object loads, Object contributors, Object dataQuality) {}

    public void insert(long athleteId, String modelVersion, String featureVersion, List<Row> rows) {
        var args = new ArrayList<Object[]>();
        for (Row r : rows) {
            args.add(new Object[] {athleteId, r.date(), modelVersion, featureVersion, r.recoveryPct(), r.band(), r.strain(),
                r.sleepPerf(), r.sleepNeed(), write(r.loads()), write(r.contributors()), write(r.dataQuality())});
        }
        template.batchUpdate("""
                INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct,
                    recovery_band, strain, sleep_performance_pct, sleep_need_min, loads, contributors, data_quality)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)""",
                args,
                new int[] {Types.BIGINT, Types.DATE, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.NUMERIC,
                    Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
    }

    /** A stored daily score, as the API returns it. */
    public record StoredDay(
            LocalDate date, String modelVersion, String featureVersion, Integer recoveryPct, String recoveryBand,
            java.math.BigDecimal strain, Integer sleepPerformancePct, Integer sleepNeedMin,
            JsonNode loads, JsonNode contributors, JsonNode dataQuality, Instant computedAt) {}

    public List<StoredDay> days(long athleteId, String modelVersion, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT * FROM derived_day_metrics WHERE athlete_id = ? AND model_version = ?
                  AND cycle_date >= coalesce(?::date, '-infinity'::date) AND cycle_date <= coalesce(?::date, 'infinity'::date)
                ORDER BY cycle_date""")
                .params(athleteId, modelVersion, from, to)
                .query((rs, i) -> {
                    try {
                        return new StoredDay(
                                rs.getObject("cycle_date", LocalDate.class), rs.getString("model_version"), rs.getString("feature_version"),
                                (Integer) rs.getObject("recovery_pct"), rs.getString("recovery_band"), rs.getBigDecimal("strain"),
                                (Integer) rs.getObject("sleep_performance_pct"), (Integer) rs.getObject("sleep_need_min"),
                                json.readTree(rs.getString("loads")), json.readTree(rs.getString("contributors")),
                                json.readTree(rs.getString("data_quality")), rs.getTimestamp("computed_at").toInstant());
                    } catch (JsonProcessingException e) {
                        throw new SQLException(e);
                    }
                })
                .list();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
