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

    public record Score(Integer recoveryPct, String band, Integer sleepNeedMin) {}

    /** Module 1 scores of one model version (the ACTIVE one), per day. */
    public Map<LocalDate, Score> scores(long athleteId, String modelVersion) {
        var out = new HashMap<LocalDate, Score>();
        jdbc.sql("""
                SELECT cycle_date, recovery_pct, recovery_band, sleep_need_min FROM derived_day_metrics
                WHERE athlete_id = ? AND model_version = ?""")
                .params(athleteId, modelVersion)
                .query(rs -> {
                    out.put(rs.getObject("cycle_date", LocalDate.class), new Score((Integer) rs.getObject("recovery_pct"),
                            rs.getString("recovery_band"), (Integer) rs.getObject("sleep_need_min")));
                });
        return out;
    }

    /** Minutes asleep per night (read only from day_features; Garmin main sleep, as the features count it). */
    public Map<LocalDate, Integer> sleptMinutes(long athleteId, String featureVersion) {
        var out = new HashMap<LocalDate, Integer>();
        jdbc.sql("SELECT cycle_date, sleep_minutes FROM day_features WHERE athlete_id = ? AND feature_version = ? AND sleep_minutes IS NOT NULL")
                .params(athleteId, featureVersion)
                .query(rs -> {
                    out.put(rs.getObject("cycle_date", LocalDate.class), rs.getInt("sleep_minutes"));
                });
        return out;
    }

    /** The first day with overnight HRV (from 2025-09-17 on the coach's data, ADR 0004). */
    public Optional<LocalDate> firstHrvDay(long athleteId, String featureVersion) {
        return jdbc.sql("SELECT min(cycle_date) FROM day_features WHERE athlete_id = ? AND feature_version = ? AND rmssd IS NOT NULL")
                .params(athleteId, featureVersion).query(LocalDate.class).optional();
    }

    /** The current check-ins (subjective_current, ADR 0018 D6), oldest first. */
    public List<Subjective.Checkin> checkins(long athleteId) {
        return jdbc.sql("""
                SELECT log_date, scope_key, rpe, soreness, niggle_location, niggle_severity, niggle_resolved, injury, illness
                FROM subjective_current WHERE athlete_id = ? ORDER BY log_date, created_at, id""")
                .param(athleteId)
                .query((rs, row) -> new Subjective.Checkin(rs.getObject("log_date", LocalDate.class), "day".equals(rs.getString("scope_key")),
                        (Integer) rs.getObject("rpe"), (Integer) rs.getObject("soreness"), rs.getString("niggle_location"),
                        (Integer) rs.getObject("niggle_severity"), rs.getBoolean("niggle_resolved"), rs.getBoolean("injury"),
                        rs.getBoolean("illness")))
                .list();
    }

    public record PlannedSession(long id, String type, String demand, Double estStrain, Double distanceKm, Integer durationMin, String status) {}

    /** Planned sessions that still count (not skipped or replaced), per day. */
    public Map<LocalDate, List<PlannedSession>> sessions(long athleteId, LocalDate from, LocalDate to) {
        var out = new HashMap<LocalDate, List<PlannedSession>>();
        jdbc.sql("""
                SELECT id, session_date, session_type, demand, est_strain, target_distance_m, target_duration_min, status
                FROM planned_session WHERE athlete_id = ? AND session_date BETWEEN ? AND ? AND status NOT IN ('SKIPPED', 'REPLACED')
                ORDER BY session_date, id""")
                .params(athleteId, from, to)
                .query(rs -> {
                    var strain = rs.getBigDecimal("est_strain");
                    var dist = rs.getBigDecimal("target_distance_m");
                    out.computeIfAbsent(rs.getObject("session_date", LocalDate.class), k -> new java.util.ArrayList<>())
                            .add(new PlannedSession(rs.getLong("id"), rs.getString("session_type"), rs.getString("demand"),
                                    strain == null ? null : strain.doubleValue(), dist == null ? null : dist.doubleValue() / 1000.0,
                                    (Integer) rs.getObject("target_duration_min"), rs.getString("status")));
                });
        return out;
    }

    public record OverrideRow(long id, Double kmPerWeek, String note, String createdBy, Instant createdAt) {}

    /** The coach's tolerance overrides, oldest first (append-only, ADR 0021 D11). */
    public List<OverrideRow> overrides(long athleteId) {
        return jdbc.sql("""
                SELECT o.id, o.km_per_week, o.note, u.username, o.created_at FROM tolerance_override o
                LEFT JOIN app_user u ON u.id = o.created_by WHERE o.athlete_id = ? ORDER BY o.created_at, o.id""")
                .param(athleteId)
                .query((rs, row) -> {
                    var km = rs.getBigDecimal("km_per_week");
                    return new OverrideRow(rs.getLong("id"), km == null ? null : km.doubleValue(), rs.getString("note"),
                            rs.getString("username"), rs.getTimestamp("created_at").toInstant());
                }).list();
    }

    public long insertOverride(long athleteId, Double kmPerWeek, String note, Long userId) {
        return jdbc.sql("INSERT INTO tolerance_override (athlete_id, km_per_week, note, created_by) VALUES (?, ?, ?, ?) RETURNING id")
                .params(athleteId, kmPerWeek == null ? null : java.math.BigDecimal.valueOf(kmPerWeek), note, userId)
                .query(Long.class).single();
    }

    /** Replaces this athlete's rows of one signals and settings version with {@code days}. */
    public void replace(long athleteId, String signalsVersion, long settingsVersion, List<DaySignals> days) {
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

    private String write(DaySignals d) {
        try {
            var node = json.createObjectNode();
            node.set("load", json.valueToTree(d.load().load()));
            node.set("foster", json.valueToTree(d.load().foster()));
            node.set("running", json.valueToTree(d.load().running()));
            node.set("readiness", json.valueToTree(d.readiness()));
            node.set("sleep", json.valueToTree(d.sleep()));
            node.set("subjective", json.valueToTree(d.subjective()));
            node.set("sessions", json.valueToTree(d.sessions()));
            node.set("tolerance", json.valueToTree(d.tolerance()));
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
