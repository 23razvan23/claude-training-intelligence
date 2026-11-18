package app.training.adaptation.checkin;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the check-in history from the effort the athlete entered on the watch (ADR 0020 D7 and its amendment).
 * One {@code subjective_log} row per activity with a usable {@code workoutRpe}: scope = the activity,
 * {@code source = 'GARMIN_SEED'}, no author. Safe to run any number of times:
 * <ul>
 *   <li>an activity that already has <b>any</b> check-in row (an earlier seed or a manual answer) is skipped.
 *       Skipping a manual answer matters because "latest wins" would otherwise let a seed written today
 *       replace what the athlete said earlier;</li>
 *   <li>a manual check-in made later is newer and wins as usual.</li>
 * </ul>
 */
@Service
public class GarminSeedService {
    public record Outcome(int activitiesWithEffort, int seeded, int alreadyChecked, int noUsableRpe) {}

    private record Candidate(long activityId, java.time.LocalDate date, Integer workoutRpe, boolean hasCheckin) {}

    private final JdbcClient jdbc;
    private final int divisor;

    public GarminSeedService(JdbcClient jdbc, @Value("${app.checkin.garmin-rpe-divisor:10}") int divisor) {
        this.jdbc = jdbc;
        this.divisor = divisor;
    }

    @Transactional
    public Outcome seed(long athleteId) {
        List<Candidate> all = jdbc.sql("""
                SELECT a.id, a.calendar_date, e.workout_rpe,
                       EXISTS (SELECT 1 FROM subjective_log s WHERE s.activity_id = a.id) AS has_checkin
                FROM activity_effort e JOIN activity a ON a.id = e.activity_id
                WHERE a.athlete_id = ? ORDER BY a.start_at""")
                .param(athleteId)
                .query((rs, i) -> new Candidate(rs.getLong("id"), rs.getObject("calendar_date", java.time.LocalDate.class),
                        (Integer) rs.getObject("workout_rpe"), rs.getBoolean("has_checkin")))
                .list();
        int seeded = 0, already = 0, unusable = 0;
        for (Candidate c : all) {
            Integer rpe = GarminEffort.rpe(c.workoutRpe(), divisor);
            if (rpe == null) {
                unusable++;
            } else if (c.hasCheckin()) {
                already++;
            } else {
                // The guard repeats the check inside the statement, so two runs at once still add one row.
                int n = jdbc.sql("""
                        INSERT INTO subjective_log (athlete_id, log_date, activity_id, rpe, source)
                        SELECT ?, ?, ?, ?, 'GARMIN_SEED'
                        WHERE NOT EXISTS (SELECT 1 FROM subjective_log s WHERE s.activity_id = ?)""")
                        .params(athleteId, c.date(), c.activityId(), rpe, c.activityId()).update();
                if (n > 0) seeded++;
                else already++;
            }
        }
        return new Outcome(all.size(), seeded, already, unusable);
    }
}
