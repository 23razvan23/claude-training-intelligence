package app.training.planning;

import app.training.planning.PlanModels.Plan;
import app.training.planning.PlanModels.Revision;
import app.training.planning.PlanModels.Session;
import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.SessionTypes.Demand;
import app.training.planning.SessionTypes.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** plan, planned_session and planned_session_revision (V3 as corrected by V22, ADR 0018). */
@Repository
public class PlanRepository {
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PlanRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- plans ----

    private final RowMapper<Plan> planMapper = (rs, n) -> new Plan(
            rs.getLong("id"), rs.getLong("athlete_id"), rs.getString("name"), rs.getString("origin"),
            rs.getString("status"), rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
            instant(rs.getTimestamp("created_at")));

    public List<Plan> plans(long athleteId) {
        return jdbc.sql("SELECT * FROM plan WHERE athlete_id = ? ORDER BY start_date DESC, id DESC")
                .param(athleteId).query(planMapper).list();
    }

    public Optional<Plan> plan(long planId) {
        return jdbc.sql("SELECT * FROM plan WHERE id = ?").param(planId).query(planMapper).optional();
    }

    public Optional<Plan> activePlan(long athleteId) {
        return jdbc.sql("SELECT * FROM plan WHERE athlete_id = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
                .param(athleteId).query(planMapper).optional();
    }

    public long insertPlan(long athleteId, String name, String origin, LocalDate start, LocalDate end) {
        return jdbc.sql("""
                INSERT INTO plan (athlete_id, name, origin, status, start_date, end_date)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?) RETURNING id""")
                .params(athleteId, name, origin, start, end).query(Long.class).single();
    }

    public void updatePlan(long planId, String name, String status, LocalDate start, LocalDate end) {
        jdbc.sql("UPDATE plan SET name = ?, status = ?, start_date = ?, end_date = ? WHERE id = ?")
                .params(name, status, start, end, planId).update();
    }

    /** Widens the plan's dates so they include {@code date} (coach decision, ADR 0019 D2). */
    public void stretchPlan(long planId, LocalDate date) {
        jdbc.sql("UPDATE plan SET start_date = LEAST(start_date, ?), end_date = GREATEST(end_date, ?) WHERE id = ?")
                .params(date, date, planId).update();
    }

    // ---- sessions ----

    private final RowMapper<Session> sessionMapper = (rs, n) -> new Session(
            rs.getLong("id"), rs.getLong("athlete_id"), (Long) rs.getObject("plan_id"),
            rs.getObject("session_date", LocalDate.class), rs.getString("session_type"),
            Demand.valueOf(rs.getString("demand")), rs.getString("title"), rs.getString("description"),
            km(rs.getBigDecimal("target_distance_m")), (Integer) rs.getObject("target_duration_min"),
            rs.getBigDecimal("est_strain"), Status.valueOf(rs.getString("status")), rs.getString("notes"),
            (Long) rs.getObject("replaces_session_id"), (Long) rs.getObject("changed_by_suggestion_id"),
            instant(rs.getTimestamp("updated_at")));

    private static final String SESSION_COLUMNS = """
            id, athlete_id, plan_id, session_date, session_type, demand, title, structure -> 0 ->> 'text' AS description,
            target_distance_m, target_duration_min, est_strain, status, notes, replaces_session_id,
            changed_by_suggestion_id, updated_at""";

    public Optional<Session> session(long sessionId) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM planned_session WHERE id = ?")
                .param(sessionId).query(sessionMapper).optional();
    }

    public List<Session> sessions(long athleteId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + """
                 FROM planned_session WHERE athlete_id = ? AND session_date BETWEEN ? AND ?
                ORDER BY session_date, id""")
                .params(athleteId, from, to).query(sessionMapper).list();
    }

    /** Like {@link #sessions} for the import preview, which may span more than the list endpoint allows. */
    public List<Session> sessionsInRange(long athleteId, LocalDate from, LocalDate to) {
        return sessions(athleteId, from, to);
    }

    public List<Session> sessionsOfPlan(long planId) {
        return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM planned_session WHERE plan_id = ? ORDER BY session_date, id")
                .param(planId).query(sessionMapper).list();
    }

    /** {@code r} is already validated and completed (demand and status filled in) by the service. */
    public long insertSession(long athleteId, long planId, SessionRequest r) {
        return jdbc.sql("""
                INSERT INTO planned_session (athlete_id, plan_id, session_date, session_type, demand, title, structure,
                    target_distance_m, target_duration_min, est_strain, status, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id""")
                .params(athleteId, planId, r.date(), r.sessionType(), r.demand().name(), title(r),
                        structure(r.description()), metres(r.targetDistanceKm()), r.targetDurationMin(), r.estStrain(),
                        r.status().name(), r.notes())
                .query(Long.class).single();
    }

    public void updateSession(long sessionId, long planId, SessionRequest r) {
        jdbc.sql("""
                UPDATE planned_session SET plan_id = ?, session_date = ?, session_type = ?, demand = ?, title = ?,
                    structure = ?::jsonb, target_distance_m = ?, target_duration_min = ?, est_strain = ?, status = ?,
                    notes = ?, updated_at = now()
                WHERE id = ?""")
                .params(planId, r.date(), r.sessionType(), r.demand().name(), title(r), structure(r.description()),
                        metres(r.targetDistanceKm()), r.targetDurationMin(), r.estStrain(), r.status().name(), r.notes(),
                        sessionId)
                .update();
    }

    public void deleteSession(long sessionId) {
        jdbc.sql("DELETE FROM planned_session WHERE id = ?").param(sessionId).update();
    }

    // ---- history ----

    public void insertRevision(long athleteId, long sessionId, String kind, Session before, Session after, Long userId) {
        jdbc.sql("""
                INSERT INTO planned_session_revision (athlete_id, planned_session_id, change_kind, before, after, changed_by)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)""")
                .params(athleteId, sessionId, kind, toJson(before), toJson(after), userId).update();
    }

    public List<Revision> revisions(long sessionId) {
        return jdbc.sql("""
                SELECT r.id, r.planned_session_id, r.change_kind, r.before::text AS before, r.after::text AS after,
                       r.suggestion_id, u.username, r.changed_at
                FROM planned_session_revision r LEFT JOIN app_user u ON u.id = r.changed_by
                WHERE r.planned_session_id = ? ORDER BY r.changed_at, r.id""")
                .param(sessionId)
                .query((rs, n) -> new Revision(rs.getLong("id"), rs.getLong("planned_session_id"),
                        rs.getString("change_kind"), node(rs, "before"), node(rs, "after"),
                        (Long) rs.getObject("suggestion_id"), rs.getString("username"),
                        instant(rs.getTimestamp("changed_at"))))
                .list();
    }

    // ---- helpers ----

    private static String title(SessionRequest r) {
        return r.title() == null ? "" : r.title().strip();
    }

    private String structure(String description) {
        if (description == null || description.isBlank()) return "[]";
        return json.createArrayNode().add(json.createObjectNode().put("text", description.strip())).toString();
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write a session as JSON", e);
        }
    }

    private JsonNode node(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new SQLException("Unreadable JSON in " + column, e);
        }
    }

    private static BigDecimal km(BigDecimal metres) {
        return metres == null ? null : metres.divide(THOUSAND, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal metres(BigDecimal km) {
        return km == null ? null : km.multiply(THOUSAND).setScale(1, RoundingMode.HALF_UP);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
