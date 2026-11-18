package app.training.adaptation.checkin;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Check-ins (stage 2-1b, ADR 0019): resolve the scope, validate, append. Never updates or deletes. */
@Service
public class CheckinService {
    private final CheckinRepository repo;
    private final AthleteRepository athletes;
    private final JdbcClient jdbc;
    private final CheckinProperties props;
    private final Clock clock;

    public CheckinService(CheckinRepository repo, AthleteRepository athletes, JdbcClient jdbc, CheckinProperties props,
                          Clock clock) {
        this.repo = repo;
        this.athletes = athletes;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public record ActivityOption(long id, String sport, Instant startAt, Integer durationS, Double distanceM) {}

    public record SessionOption(long id, String title, String sessionType, String demand, String status) {}

    /** What the form needs for one day: the choices of scope and the answers already given (to prefill). */
    public record Options(LocalDate today, LocalDate date, String timezone, List<ActivityOption> activities,
                          List<SessionOption> sessions, List<CheckinView> current, List<String> locations,
                          int maxBackdateDays) {}

    public LocalDate today(long athleteId) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        return LocalDate.now(clock.withZone(ZoneId.of(athlete.timezone())));
    }

    public Options options(long athleteId, LocalDate date) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(athlete.timezone())));
        LocalDate day = date == null ? today : date;
        var activities = jdbc.sql("""
                SELECT id, sport, start_at, duration_s, distance_m FROM activity
                WHERE athlete_id = ? AND source = 'GARMIN' AND calendar_date = ? ORDER BY start_at""")
                .params(athleteId, day)
                .query((rs, i) -> new ActivityOption(rs.getLong("id"), rs.getString("sport"),
                        rs.getTimestamp("start_at").toInstant(), (Integer) rs.getObject("duration_s"),
                        rs.getBigDecimal("distance_m") == null ? null : rs.getBigDecimal("distance_m").doubleValue()))
                .list();
        var sessions = jdbc.sql("""
                SELECT id, title, session_type, demand, status FROM planned_session
                WHERE athlete_id = ? AND session_date = ? AND status IN ('PLANNED', 'DONE') ORDER BY id""")
                .params(athleteId, day)
                .query((rs, i) -> new SessionOption(rs.getLong("id"), rs.getString("title"), rs.getString("session_type"),
                        rs.getString("demand"), rs.getString("status")))
                .list();
        return new Options(today, day, athlete.timezone(), activities, sessions, repo.current(athleteId, day, day),
                CheckinValidator.LOCATIONS, props.maxBackdateDays());
    }

    /** Appends one row and returns the check-in that now counts for its day and scope. */
    public CheckinView create(long athleteId, CheckinRequest request, CurrentUser user) {
        LocalDate today = today(athleteId);
        LocalDate date = request.date() == null ? today : request.date();
        if (request.plannedSessionId() != null) {
            date = jdbc.sql("SELECT session_date FROM planned_session WHERE id = ? AND athlete_id = ?")
                    .params(request.plannedSessionId(), athleteId).query(LocalDate.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Planned session"));
        } else if (request.activityId() != null) {
            date = jdbc.sql("SELECT calendar_date FROM activity WHERE id = ? AND athlete_id = ?")
                    .params(request.activityId(), athleteId).query(LocalDate.class).optional()
                    .orElseThrow(() -> ApiException.notFound("Activity"));
        }
        CheckinRequest valid = CheckinValidator.validate(request, date, today, props.maxBackdateDays());
        boolean onBehalf = user.isCoach() && !Long.valueOf(athleteId).equals(user.athleteId());
        long id = repo.insert(athleteId, date, valid, user.id(), onBehalf);
        return repo.current(athleteId, date, date).stream()
                .filter(c -> c.id() == id || sameScope(c, valid))
                .findFirst().orElseThrow();
    }

    private static boolean sameScope(CheckinView c, CheckinRequest r) {
        return r.plannedSessionId() != null ? r.plannedSessionId().equals(c.plannedSessionId())
                : r.activityId() != null ? r.activityId().equals(c.activityId())
                : c.plannedSessionId() == null && c.activityId() == null;
    }

    public List<CheckinView> recent(long athleteId, int days) {
        LocalDate today = today(athleteId);
        return repo.current(athleteId, today.minusDays(days), today);
    }

    public List<CheckinView> recentForAll(int days) {
        return repo.currentForAll(LocalDate.now(clock).minusDays(days + 1L), 200);
    }
}
