package app.training.adaptation.checkin;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert-only for subjective_log; every read goes through the view subjective_current (ADR 0018 D6). */
@Repository
public class CheckinRepository {
    private final JdbcClient jdbc;

    public CheckinRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long athleteId, LocalDate date, CheckinRequest r, Long userId, boolean onBehalf) {
        return jdbc.sql("""
                INSERT INTO subjective_log (athlete_id, log_date, activity_id, planned_session_id, rpe, soreness,
                    niggle_location, niggle_severity, niggle_resolved, injury, illness, note, created_by, on_behalf)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(athleteId, date, r.activityId(), r.plannedSessionId(), r.rpe(), r.soreness(), r.niggleLocation(),
                        r.niggleSeverity(), r.niggleResolved(), r.injury(), r.illness(), r.note(), userId, onBehalf)
                .query(Long.class).single();
    }

    private static final String SELECT = """
            SELECT c.id, c.athlete_id, a.name AS athlete_name, c.log_date, c.scope_key, c.planned_session_id,
                   c.activity_id, c.rpe, c.soreness, c.niggle_location, c.niggle_severity, c.niggle_resolved,
                   c.injury, c.illness, c.note, c.source, c.on_behalf, c.created_at
            FROM subjective_current c JOIN athlete a ON a.id = c.athlete_id""";

    /** The current check-ins of one athlete between two days, newest day first. */
    public List<CheckinView> current(long athleteId, LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT + " WHERE c.athlete_id = ? AND c.log_date BETWEEN ? AND ? ORDER BY c.log_date DESC, c.created_at DESC, c.id DESC")
                .params(athleteId, from, to).query(CheckinRepository::map).list();
    }

    /** The current check-ins of every athlete since a day (coach overview), newest first. */
    public List<CheckinView> currentForAll(LocalDate from, int limit) {
        return jdbc.sql(SELECT + " WHERE c.log_date >= ? ORDER BY c.log_date DESC, c.created_at DESC, c.id DESC LIMIT ?")
                .params(from, limit).query(CheckinRepository::map).list();
    }

    private static CheckinView map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String key = rs.getString("scope_key");
        return new CheckinView(rs.getLong("id"), rs.getLong("athlete_id"), rs.getString("athlete_name"),
                rs.getObject("log_date", LocalDate.class), key.startsWith("session:") ? "session" : key.startsWith("activity:") ? "activity" : "day",
                (Long) rs.getObject("planned_session_id"), (Long) rs.getObject("activity_id"),
                (Integer) rs.getObject("rpe"), (Integer) rs.getObject("soreness"), rs.getString("niggle_location"),
                (Integer) rs.getObject("niggle_severity"), rs.getBoolean("niggle_resolved"), rs.getBoolean("injury"),
                rs.getBoolean("illness"), rs.getString("note"), rs.getString("source"), rs.getBoolean("on_behalf"),
                rs.getTimestamp("created_at").toInstant());
    }
}
