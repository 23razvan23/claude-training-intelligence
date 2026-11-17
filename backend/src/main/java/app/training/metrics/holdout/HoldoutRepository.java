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

    /**
     * Days from {@code from} on whose stored score (this model version) was calculated from a
     * night that came from a Garmin sync, i.e. with Garmin's live breathing rate (ADR 0015 D12,
     * D13). The night is the one the score actually used: for each Garmin sleep record, the newest
     * version imported by the time the score was written ({@code computed_at}), as
     * {@code sleep_session_current} would have returned it then. So a day keeps waiting after an
     * export arrives until it is rescored (Recompute features), and a retired version's days,
     * never rescored, stay as they were. Read-only.
     */
    public java.util.Set<LocalDate> syncedNights(long athleteId, String modelVersion, LocalDate from) {
        return new java.util.HashSet<>(jdbc.sql("""
                SELECT dm.cycle_date FROM derived_day_metrics dm
                WHERE dm.athlete_id = ? AND dm.model_version = ? AND dm.cycle_date >= ?
                  AND EXISTS (
                      SELECT 1 FROM sleep_session s JOIN import_batch b ON b.id = s.import_batch_id
                      WHERE s.athlete_id = dm.athlete_id AND s.source = 'GARMIN' AND NOT s.is_nap
                        AND s.cycle_date = dm.cycle_date AND s.ingested_at <= dm.computed_at AND b.kind = 'GARMIN_SYNC'
                        AND NOT EXISTS (
                            SELECT 1 FROM sleep_session n
                            WHERE n.athlete_id = s.athlete_id AND n.source = s.source
                              AND split_part(n.source_id, '@', 1) = split_part(s.source_id, '@', 1)
                              AND n.ingested_at > s.ingested_at AND n.ingested_at <= dm.computed_at))""")
                .params(athleteId, modelVersion, from)
                .query(LocalDate.class).list());
    }

    /**
     * When the coach pressed Recompute features for the athlete between the two instants (either
     * may be null: open). Runs after a Garmin sync are left out: a morning sync recomputes every
     * day, and marking each would bury the coach's own runs (ADR 0015 D8).
     */
    public List<Instant> recomputeRuns(long athleteId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT ran_at FROM feature_recompute_log
                WHERE athlete_id = ? AND source = 'manual' AND ran_at >= coalesce(?::timestamptz, '-infinity'::timestamptz)
                  AND ran_at <= coalesce(?::timestamptz, 'infinity'::timestamptz)
                ORDER BY ran_at""")
                .params(athleteId, from == null ? null : java.sql.Timestamp.from(from), to == null ? null : java.sql.Timestamp.from(to))
                .query((rs, i) -> rs.getTimestamp(1).toInstant())
                .list();
    }

    /** What has arrived so far, for the "keep it fed" status on the Holdout view (stage 1b-3 PR 3). */
    public record FeedRow(LocalDate lastWhoopDay, LocalDate lastGarminDay, Instant lastWhoopImport, Instant lastGarminImport,
                          Instant lastImport, Instant lastRecompute) {}

    public FeedRow feed(long athleteId) {
        return jdbc.sql("""
                SELECT (SELECT max(cycle_date) FROM whoop_reference WHERE athlete_id = :a AND cycle_end IS NOT NULL) AS last_whoop_day,
                       (SELECT max(calendar_date) FROM daily_wellness WHERE athlete_id = :a AND source = 'GARMIN') AS last_garmin_day,
                       (SELECT max(finished_at) FROM import_batch WHERE athlete_id = :a AND status = 'SUCCEEDED'
                            AND kind = 'WHOOP_EXPORT') AS last_whoop_import,
                       (SELECT max(finished_at) FROM import_batch WHERE athlete_id = :a AND status = 'SUCCEEDED'
                            AND kind IN ('GARMIN_EXPORT', 'GARMIN_SYNC', 'FIT_UPLOAD')) AS last_garmin_import,
                       (SELECT max(finished_at) FROM import_batch WHERE athlete_id = :a AND status = 'SUCCEEDED') AS last_import,
                       (SELECT max(ran_at) FROM feature_recompute_log WHERE athlete_id = :a) AS last_recompute""")
                .param("a", athleteId)
                .query((rs, i) -> new FeedRow(
                        rs.getObject("last_whoop_day", LocalDate.class),
                        rs.getObject("last_garmin_day", LocalDate.class),
                        instant(rs.getTimestamp("last_whoop_import")),
                        instant(rs.getTimestamp("last_garmin_import")),
                        instant(rs.getTimestamp("last_import")),
                        instant(rs.getTimestamp("last_recompute"))))
                .single();
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Records one Recompute features run (ADR 0013 D6); {@code source} is 'manual' or 'garmin-sync' (ADR 0015 D8). */
    public void logRecompute(long athleteId, Long userId, String source, String featureVersion, int featureDays,
                             String scoredModelVersion, int scoredDays) {
        jdbc.sql("""
                INSERT INTO feature_recompute_log (athlete_id, ran_by, source, feature_version, feature_days, scored_model_version, scored_days)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(athleteId, userId, source, featureVersion, featureDays, scoredModelVersion, scoredDays)
                .update();
    }
}
