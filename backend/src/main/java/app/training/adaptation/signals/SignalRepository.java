package app.training.adaptation.signals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the inputs of the signals and writes day_signals (ADR 0018 D7, D8). Read-only on everything else: the daily
 * total load comes from day_features (the same column the Trends screen draws, so both agree, ADR 0018 D9); the
 * running-only load, km and minutes from activity_with_cycle and activity_load. Nothing here writes day_features.
 */
@Repository
public class SignalRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ObjectMapper json;

    public SignalRepository(JdbcClient jdbc, JdbcTemplate template, ObjectMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    public record TotalLoad(Double edwards, Double banister) {}

    public record Running(Double edwards, Double banister, double km, double minutes) {}

    public Optional<LocalDate> firstFeatureDay(long athleteId, String featureVersion) {
        return jdbc.sql("SELECT min(cycle_date) FROM day_features WHERE athlete_id = ? AND feature_version = ?")
                .params(athleteId, featureVersion).query(LocalDate.class).optional();
    }

    /** Daily total load per day; a null value = no usable load that day (unknown), exactly as day_features stores it. */
    public Map<LocalDate, TotalLoad> totalLoads(long athleteId, String featureVersion) {
        var out = new HashMap<LocalDate, TotalLoad>();
        jdbc.sql("SELECT cycle_date, day_load_edwards, day_load_banister FROM day_features WHERE athlete_id = ? AND feature_version = ?")
                .params(athleteId, featureVersion)
                .query(rs -> {
                    var e = rs.getBigDecimal("day_load_edwards");
                    var b = rs.getBigDecimal("day_load_banister");
                    out.put(rs.getObject("cycle_date", LocalDate.class),
                            new TotalLoad(e == null ? null : e.doubleValue(), b == null ? null : b.doubleValue()));
                });
        return out;
    }

    /** Running activities per cycle day (Garmin, as the features count them): load, km, minutes. */
    public Map<LocalDate, Running> running(long athleteId) {
        var out = new HashMap<LocalDate, Running>();
        jdbc.sql("""
                SELECT ac.cycle_date, sum(l.trimp_edwards) AS edwards, sum(l.trimp_banister) AS banister,
                       coalesce(sum(ac.distance_m), 0) / 1000.0 AS km, coalesce(sum(ac.duration_s), 0) / 60.0 AS minutes
                FROM activity_with_cycle ac
                LEFT JOIN activity_load l ON l.activity_id = ac.id
                WHERE ac.athlete_id = ? AND ac.source = 'GARMIN' AND ac.sport = 'running'
                GROUP BY ac.cycle_date""")
                .param(athleteId)
                .query(rs -> {
                    var e = rs.getBigDecimal("edwards");
                    var b = rs.getBigDecimal("banister");
                    out.put(rs.getObject("cycle_date", LocalDate.class), new Running(e == null ? null : e.doubleValue(),
                            b == null ? null : b.doubleValue(), rs.getDouble("km"), rs.getDouble("minutes")));
                });
        return out;
    }

    /** Replaces this athlete's rows of one signals and settings version with {@code days}. */
    public void replace(long athleteId, String signalsVersion, long settingsVersion, List<LoadSignals.Day> days) {
        jdbc.sql("DELETE FROM day_signals WHERE athlete_id = ? AND signals_version = ? AND settings_version = ?")
                .params(athleteId, signalsVersion, settingsVersion).update();
        template.batchUpdate("""
                INSERT INTO day_signals (athlete_id, cycle_date, signals_version, settings_version, signals)
                VALUES (?, ?, ?, ?, ?::jsonb)""", days, 500, (ps, d) -> {
            ps.setLong(1, athleteId);
            ps.setDate(2, Date.valueOf(d.date()));
            ps.setString(3, signalsVersion);
            ps.setLong(4, settingsVersion);
            ps.setString(5, write(d));
        });
    }

    public record Stored(LocalDate date, String signalsVersion, long settingsVersion, JsonNode signals, Instant computedAt) {}

    public List<Stored> read(long athleteId, String signalsVersion, long settingsVersion, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT cycle_date, signals_version, settings_version, signals::text AS signals, computed_at FROM day_signals
                WHERE athlete_id = ? AND signals_version = ? AND settings_version = ? AND cycle_date BETWEEN ? AND ?
                ORDER BY cycle_date""")
                .params(athleteId, signalsVersion, settingsVersion, from, to)
                .query((rs, row) -> {
                    try {
                        return new Stored(rs.getObject("cycle_date", LocalDate.class), rs.getString("signals_version"),
                                rs.getLong("settings_version"), json.readTree(rs.getString("signals")),
                                rs.getTimestamp("computed_at").toInstant());
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }).list();
    }

    private String write(LoadSignals.Day d) {
        try {
            var node = json.createObjectNode();
            node.set("load", json.valueToTree(d.load()));
            node.set("foster", json.valueToTree(d.foster()));
            node.set("running", json.valueToTree(d.running()));
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
