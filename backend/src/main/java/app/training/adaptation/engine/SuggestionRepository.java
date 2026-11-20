package app.training.adaptation.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * adaptation_suggestion and the plan as the engine reads it (ADR 0018 D3-D5, ADR 0022). The database computes
 * {@code dedup_key} and refuses a second PENDING copy of the same suggestion.
 */
@Repository
public class SuggestionRepository {
    /** First half of the advisory lock key that serialises engine runs per athlete (the second half is the athlete id). */
    private static final int LOCK_CLASS = 2003;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public SuggestionRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One stored suggestion, as the API shows it. */
    public record Suggestion(long id, long athleteId, Long plannedSessionId, Long counterpartSessionId, LocalDate forDate, String action,
                             JsonNode before, JsonNode after, JsonNode reasons, String confidence, JsonNode inputsSnapshot, String status,
                             String coachNote, String rulesVersion, Long settingsVersion, String signalsVersion, String modelVersion,
                             LocalDate engineRunDate, String decidedBy, Instant decidedAt, Instant createdAt) {}

    /** Engine runs for one athlete never overlap (the morning sync, a check-in and the coach's button can coincide). */
    public void lock(long athleteId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(?, ?)").params(LOCK_CLASS, (int) athleteId).query((rs, n) -> 1).list();
    }

    public List<Model.Session> sessions(long athleteId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT id, plan_id, session_date, session_type, demand, title, structure -> 0 ->> 'text' AS description,
                       target_distance_m, target_duration_min, est_strain, status, is_taper, notes
                FROM planned_session WHERE athlete_id = ? AND session_date BETWEEN ? AND ? ORDER BY session_date, id""")
                .params(athleteId, from, to)
                .query((rs, n) -> {
                    BigDecimal m = rs.getBigDecimal("target_distance_m");
                    BigDecimal strain = rs.getBigDecimal("est_strain");
                    return new Model.Session(rs.getLong("id"), (Long) rs.getObject("plan_id"), rs.getObject("session_date", LocalDate.class),
                            rs.getString("session_type"), rs.getString("demand"), rs.getString("title"), rs.getString("description"),
                            m == null ? null : m.doubleValue() / 1000.0, (Integer) rs.getObject("target_duration_min"),
                            strain == null ? null : strain.doubleValue(), rs.getString("status"), rs.getBoolean("is_taper"), rs.getString("notes"));
                }).list();
    }

    /** Sessions in a suggestion decided (accepted, edited or rejected) from a run on {@code runDate}. */
    public Set<Long> decidedSessions(long athleteId, LocalDate runDate) {
        var out = new HashSet<Long>();
        jdbc.sql("""
                SELECT planned_session_id, counterpart_session_id FROM adaptation_suggestion
                WHERE athlete_id = ? AND engine_run_date = ? AND status IN ('ACCEPTED', 'EDITED', 'REJECTED')""")
                .params(athleteId, runDate)
                .query(rs -> {
                    Long a = (Long) rs.getObject(1), b = (Long) rs.getObject(2);
                    if (a != null) out.add(a);
                    if (b != null) out.add(b);
                });
        return out;
    }

    /** Was a volume-restoring suggestion (ADD) accepted from a run on or after {@code since}? */
    public boolean restoreAcceptedSince(long athleteId, LocalDate since) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM adaptation_suggestion WHERE athlete_id = ? AND action = 'ADD'
                    AND status IN ('ACCEPTED', 'EDITED') AND engine_run_date >= ?)""")
                .params(athleteId, since).query(Boolean.class).single();
    }

    public List<Suggestion> pending(long athleteId) {
        return jdbc.sql(SELECT + " WHERE s.athlete_id = ? AND s.status = 'PENDING' ORDER BY s.for_date, s.id").param(athleteId)
                .query(this::map).list();
    }

    public List<Suggestion> list(long athleteId, String status, LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT + " WHERE s.athlete_id = ? AND (?::text IS NULL OR s.status = ?) AND s.for_date BETWEEN ? AND ? "
                        + "ORDER BY s.for_date, s.id")
                .params(athleteId, status, status, from, to).query(this::map).list();
    }

    public void expire(long id) {
        jdbc.sql("UPDATE adaptation_suggestion SET status = 'EXPIRED' WHERE id = ? AND status = 'PENDING'").param(id).update();
    }

    public record NewSuggestion(long athleteId, Long sessionId, Long counterpartId, LocalDate forDate, String action, JsonNode before,
                                JsonNode after, JsonNode reasons, JsonNode inputs, String confidence, String rulesVersion,
                                String modelVersion, long settingsVersion, String signalsVersion, LocalDate engineRunDate) {}

    public long insert(NewSuggestion s) {
        return jdbc.sql("""
                INSERT INTO adaptation_suggestion (athlete_id, planned_session_id, counterpart_session_id, for_date, action, before, after,
                    reasons, inputs_snapshot, confidence, rules_version, model_version, settings_version, signals_version, engine_run_date)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(s.athleteId(), s.sessionId(), s.counterpartId(), s.forDate(), s.action(), write(s.before()), write(s.after()),
                        write(s.reasons()), write(s.inputs()), s.confidence(), s.rulesVersion(), s.modelVersion(), s.settingsVersion(),
                        s.signalsVersion(), s.engineRunDate())
                .query(Long.class).single();
    }

    private static final String SELECT = """
            SELECT s.id, s.athlete_id, s.planned_session_id, s.counterpart_session_id, s.for_date, s.action, s.before::text AS before,
                   s.after::text AS after, s.reasons::text AS reasons, s.confidence, s.inputs_snapshot::text AS inputs, s.status,
                   s.coach_note, s.rules_version, s.settings_version, s.signals_version, s.model_version, s.engine_run_date,
                   u.username AS decided_by, s.decided_at, s.created_at
            FROM adaptation_suggestion s LEFT JOIN app_user u ON u.id = s.decided_by""";

    private Suggestion map(ResultSet rs, int n) throws SQLException {
        var decided = rs.getTimestamp("decided_at");
        return new Suggestion(rs.getLong("id"), rs.getLong("athlete_id"), (Long) rs.getObject("planned_session_id"),
                (Long) rs.getObject("counterpart_session_id"), rs.getObject("for_date", LocalDate.class), rs.getString("action"),
                read(rs.getString("before")), read(rs.getString("after")), read(rs.getString("reasons")), rs.getString("confidence"),
                read(rs.getString("inputs")), rs.getString("status"), rs.getString("coach_note"), rs.getString("rules_version"),
                (Long) rs.getObject("settings_version"), rs.getString("signals_version"), rs.getString("model_version"),
                rs.getObject("engine_run_date", LocalDate.class), rs.getString("decided_by"), decided == null ? null : decided.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private JsonNode read(String text) {
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(JsonNode node) {
        if (node == null) return null;
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
