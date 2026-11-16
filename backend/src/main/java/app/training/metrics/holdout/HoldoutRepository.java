package app.training.metrics.holdout;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads Whoop's finished cycles and the Recompute features log for the live comparison; writes only that log. */
@Repository
public class HoldoutRepository {
    private final JdbcClient jdbc;

    public HoldoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whoop's finished cycles from {@code from} on, straight from the imported rows (ADR 0013 D1).
     * When two cycles share a wake day, the later one counts, as in day_features. With
     * {@code importedBy} set, only rows imported by then are read: a retired version's comparison
     * stays as it was when it was retired (H1).
     */
    public List<HoldoutEvaluator.WhoopDay> whoop(long athleteId, LocalDate from, Instant importedBy) {
        return jdbc.sql("""
                SELECT DISTINCT ON (cycle_date) cycle_date, recovery_pct, day_strain, sleep_performance_pct, sleep_need_min
                FROM whoop_reference
                WHERE athlete_id = ? AND cycle_end IS NOT NULL AND cycle_date >= ?
                  AND ingested_at <= coalesce(?::timestamptz, 'infinity'::timestamptz)
                ORDER BY cycle_date, cycle_start DESC""")
                .params(athleteId, from, importedBy == null ? null : java.sql.Timestamp.from(importedBy))
                .query((rs, i) -> new HoldoutEvaluator.WhoopDay(
                        rs.getObject("cycle_date", LocalDate.class),
                        (Integer) rs.getObject("recovery_pct"),
                        rs.getBigDecimal("day_strain") == null ? null : rs.getBigDecimal("day_strain").doubleValue(),
                        (Integer) rs.getObject("sleep_performance_pct"),
                        (Integer) rs.getObject("sleep_need_min")))
                .list();
    }

    /** When Recompute features ran for the athlete between the two instants (either may be null: open). */
    public List<Instant> recomputeRuns(long athleteId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT ran_at FROM feature_recompute_log
                WHERE athlete_id = ? AND ran_at >= coalesce(?::timestamptz, '-infinity'::timestamptz)
                  AND ran_at <= coalesce(?::timestamptz, 'infinity'::timestamptz)
                ORDER BY ran_at""")
                .params(athleteId, from == null ? null : java.sql.Timestamp.from(from), to == null ? null : java.sql.Timestamp.from(to))
                .query((rs, i) -> rs.getTimestamp(1).toInstant())
                .list();
    }

    /** Records one Recompute features run (ADR 0013 D6). */
    public void logRecompute(long athleteId, Long userId, String featureVersion, int featureDays, String scoredModelVersion, int scoredDays) {
        jdbc.sql("""
                INSERT INTO feature_recompute_log (athlete_id, ran_by, feature_version, feature_days, scored_model_version, scored_days)
                VALUES (?, ?, ?, ?, ?, ?)""")
                .params(athleteId, userId, featureVersion, featureDays, scoredModelVersion, scoredDays)
                .update();
    }
}
