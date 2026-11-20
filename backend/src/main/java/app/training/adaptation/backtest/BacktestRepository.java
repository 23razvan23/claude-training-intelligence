package app.training.adaptation.backtest;

import app.training.adaptation.signals.Subjective;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads what the backtest needs beyond the signals' own queries, and writes its results to backtest_run,
 * backtest_day and backtest_suggestion (V26, ADR 0023 D5). Never writes a live table.
 */
@Repository
public class BacktestRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public BacktestRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Every check-in row (not the view: "latest wins" must be applied as of each morning), with what dates it. */
    public List<AsOf.CheckinRow> checkinRows(long athleteId) {
        return jdbc.sql("""
                SELECT s.id, s.log_date, coalesce('session:' || s.planned_session_id, 'activity:' || s.activity_id, 'day') AS scope_key,
                       s.created_at, s.source, ac.cycle_date AS activity_cycle_date, s.rpe, s.soreness, s.niggle_location,
                       s.niggle_severity, s.niggle_resolved, s.injury, s.illness
                FROM subjective_log s LEFT JOIN activity_with_cycle ac ON ac.id = s.activity_id
                WHERE s.athlete_id = ? ORDER BY s.log_date, s.created_at, s.id""")
                .param(athleteId)
                .query((rs, n) -> {
                    LocalDate day = rs.getObject("log_date", LocalDate.class);
                    String scope = rs.getString("scope_key");
                    return new AsOf.CheckinRow(rs.getLong("id"), day, scope, rs.getTimestamp("created_at").toInstant(), rs.getString("source"),
                            rs.getObject("activity_cycle_date", LocalDate.class),
                            new Subjective.Checkin(day, "day".equals(scope), (Integer) rs.getObject("rpe"), (Integer) rs.getObject("soreness"),
                                    rs.getString("niggle_location"), (Integer) rs.getObject("niggle_severity"), rs.getBoolean("niggle_resolved"),
                                    rs.getBoolean("injury"), rs.getBoolean("illness")));
                }).list();
    }

    /** Garmin activities with their heart-rate-reserve zone minutes (zone 4 = index 5, zone 5 = index 6 in SQL). */
    public List<ActivityPlan.PlanActivity> activities(long athleteId) {
        return jdbc.sql("""
                SELECT ac.id, ac.calendar_date, ac.cycle_date, ac.sport, ac.source_sport, ac.duration_s, ac.distance_m,
                       l.hrr_zone_minutes[5] AS z4, l.hrr_zone_minutes[6] AS z5
                FROM activity_with_cycle ac LEFT JOIN activity_load l ON l.activity_id = ac.id
                WHERE ac.athlete_id = ? AND ac.source = 'GARMIN' ORDER BY ac.start_at, ac.id""")
                .param(athleteId)
                .query((rs, n) -> new ActivityPlan.PlanActivity(rs.getLong("id"), rs.getObject("calendar_date", LocalDate.class),
                        rs.getObject("cycle_date", LocalDate.class), rs.getString("sport"), rs.getString("source_sport"),
                        (Integer) rs.getObject("duration_s"), dbl(rs.getBigDecimal("distance_m")), dbl(rs.getBigDecimal("z4")),
                        dbl(rs.getBigDecimal("z5")))).list();
    }

    public NavigableSet<LocalDate> hrvDays(long athleteId, String featureVersion) {
        return new TreeSet<>(jdbc.sql("SELECT cycle_date FROM day_features WHERE athlete_id = ? AND feature_version = ? AND rmssd IS NOT NULL")
                .params(athleteId, featureVersion).query(LocalDate.class).list());
    }

    /** Garmin daily summaries stored again with new values (ADR 0010: source id {@code <id>@<batch>}). */
    public List<AsOf.Revision> wellnessRevisions(long athleteId) {
        return jdbc.sql("SELECT calendar_date, ingested_at FROM daily_wellness WHERE athlete_id = ? AND source = 'GARMIN' AND source_id LIKE '%@%'")
                .param(athleteId)
                .query((rs, n) -> new AsOf.Revision(rs.getObject("calendar_date", LocalDate.class), rs.getTimestamp("ingested_at").toInstant()))
                .list();
    }

    public long insertRun(long athleteId, BacktestParams p, String rulesVersion, String signalsVersion, long settingsVersion,
                          String modelVersion, Long userId) {
        return jdbc.sql("""
                INSERT INTO backtest_run (athlete_id, from_date, to_date, params, rules_version, signals_version, settings_version,
                    model_version, created_by) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id""")
                .params(athleteId, p.from(), p.to(), write(json.valueToTree(p)), rulesVersion, signalsVersion, settingsVersion, modelVersion, userId)
                .query(Long.class).single();
    }

    public void insertDay(long runId, LocalDate morning, JsonNode today, JsonNode yesterday, JsonNode health, JsonNode notes) {
        jdbc.sql("INSERT INTO backtest_day (run_id, morning, today, yesterday, health, notes) VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb)")
                .params(runId, morning, write(today), write(yesterday), write(health), write(notes)).update();
    }

    public record NewSuggestion(long runId, LocalDate morning, LocalDate forDate, String rule, String action, Long activityId,
                                Long counterpartActivityId, JsonNode before, JsonNode after, JsonNode reasons, String confidence,
                                JsonNode inputs) {}

    public long insertSuggestion(NewSuggestion s) {
        return jdbc.sql("""
                INSERT INTO backtest_suggestion (run_id, morning, for_date, rule, action, activity_id, counterpart_activity_id, before, after,
                    reasons, confidence, inputs_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb) RETURNING id""")
                .params(s.runId(), s.morning(), s.forDate(), s.rule(), s.action(), s.activityId(), s.counterpartActivityId(), write(s.before()),
                        write(s.after()), write(s.reasons()), s.confidence(), write(s.inputs()))
                .query(Long.class).single();
    }

    public void setSummary(long runId, JsonNode summary) {
        jdbc.sql("UPDATE backtest_run SET summary = ?::jsonb WHERE id = ?").params(write(summary), runId).update();
    }

    public record Run(long id, long athleteId, LocalDate from, LocalDate to, JsonNode params, String rulesVersion, String signalsVersion,
                      long settingsVersion, String modelVersion, JsonNode summary, Instant createdAt) {}

    public List<Run> runs(long athleteId) {
        return jdbc.sql("""
                SELECT id, athlete_id, from_date, to_date, params::text AS params, rules_version, signals_version, settings_version,
                       model_version, summary::text AS summary, created_at FROM backtest_run WHERE athlete_id = ? ORDER BY created_at DESC, id DESC""")
                .param(athleteId)
                .query((rs, n) -> new Run(rs.getLong("id"), rs.getLong("athlete_id"), rs.getObject("from_date", LocalDate.class),
                        rs.getObject("to_date", LocalDate.class), read(rs.getString("params")), rs.getString("rules_version"),
                        rs.getString("signals_version"), rs.getLong("settings_version"), rs.getString("model_version"),
                        read(rs.getString("summary")), rs.getTimestamp("created_at").toInstant())).list();
    }

    private static Double dbl(BigDecimal b) {
        return b == null ? null : b.doubleValue();
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(JsonNode node) {
        try {
            return node == null ? null : json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
