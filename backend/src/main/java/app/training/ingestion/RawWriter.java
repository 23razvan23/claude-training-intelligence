package app.training.ingestion;

import app.training.ingestion.RawRows.Activity;
import app.training.ingestion.RawRows.JournalEntry;
import app.training.ingestion.RawRows.Sleep;
import app.training.ingestion.RawRows.StreamPoint;
import app.training.ingestion.RawRows.Wellness;
import app.training.ingestion.RawRows.WhoopCycle;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single write path into the raw tables. Every insert is ON CONFLICT DO
 * NOTHING against the table's dedup key, so imports are idempotent; inserted
 * and duplicate rows are counted into the batch's {@link ImportStats}.
 */
@Component
public class RawWriter {
    /** 8 parameters per row; 2,000 rows = 16,000 parameters, under PostgreSQL's 32,767 limit. */
    static final int STREAM_ROWS_PER_STATEMENT = 2_000;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public RawWriter(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public record Ctx(long athleteId, long batchId, ImportStats stats, ImportProgress progress) {
        public Ctx(long athleteId, long batchId, ImportStats stats) {
            this(athleteId, batchId, stats, ImportProgress.NONE);
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static void count(Ctx ctx, String table, int updated) {
        if (updated > 0) ctx.stats().inserted(table, updated);
        else ctx.stats().duplicates(table, 1);
    }

    /** The source id of a revision row (ADR 0010): the record's own id, then '@' and the import batch. */
    static String revisionId(String sourceId, long batchId) {
        return sourceId + "@" + batchId;
    }

    /**
     * A daily wellness record. When the same record (source, id, day) is already stored, a
     * Garmin record whose values differ from every stored version of it is kept as a revision
     * (ADR 0010): the readers then use the newest. Identical values are a duplicate, so
     * re-importing any export inserts nothing.
     */
    public void wellness(Ctx ctx, Wellness w) {
        int n = jdbc.sql("""
                INSERT INTO daily_wellness (athlete_id, source, source_id, calendar_date, resting_hr, hrv_rmssd_ms,
                    hrv_status, respiration_rate, spo2_pct, stress_avg, body_battery_high, body_battery_low, import_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""")
                .params(ctx.athleteId(), w.source(), w.sourceId(), w.calendarDate(), w.restingHr(), w.hrvRmssdMs(),
                        w.hrvStatus(), w.respirationRate(), w.spo2Pct(), w.stressAvg(), w.bodyBatteryHigh(),
                        w.bodyBatteryLow(), ctx.batchId())
                .update();
        if (n == 0 && RawRows.GARMIN.equals(w.source())) {
            // Values are cast to the column types first, so a value the column rounds is compared as stored.
            n = jdbc.sql("""
                    INSERT INTO daily_wellness (athlete_id, source, source_id, calendar_date, resting_hr, hrv_rmssd_ms,
                        hrv_status, respiration_rate, spo2_pct, stress_avg, body_battery_high, body_battery_low, import_batch_id)
                    SELECT v.* FROM (VALUES (CAST(:athlete AS BIGINT), CAST(:source AS TEXT), CAST(:revision AS TEXT),
                        CAST(:day AS DATE), CAST(:rhr AS INT), CAST(:hrv AS NUMERIC(6, 1)), CAST(:status AS TEXT),
                        CAST(:resp AS NUMERIC(4, 1)), CAST(:spo2 AS NUMERIC(4, 1)), CAST(:stress AS INT), CAST(:bbHigh AS INT),
                        CAST(:bbLow AS INT), CAST(:batch AS BIGINT)))
                        AS v (athlete_id, source, source_id, calendar_date, resting_hr, hrv_rmssd_ms, hrv_status,
                              respiration_rate, spo2_pct, stress_avg, body_battery_high, body_battery_low, import_batch_id)
                    WHERE NOT EXISTS (
                        SELECT 1 FROM daily_wellness x
                        WHERE x.athlete_id = v.athlete_id AND x.source = v.source AND x.calendar_date = v.calendar_date
                          AND split_part(x.source_id, '@', 1) = :sourceId
                          AND x.resting_hr IS NOT DISTINCT FROM v.resting_hr
                          AND x.hrv_rmssd_ms IS NOT DISTINCT FROM v.hrv_rmssd_ms
                          AND x.hrv_status IS NOT DISTINCT FROM v.hrv_status
                          AND x.respiration_rate IS NOT DISTINCT FROM v.respiration_rate
                          AND x.spo2_pct IS NOT DISTINCT FROM v.spo2_pct
                          AND x.stress_avg IS NOT DISTINCT FROM v.stress_avg
                          AND x.body_battery_high IS NOT DISTINCT FROM v.body_battery_high
                          AND x.body_battery_low IS NOT DISTINCT FROM v.body_battery_low)
                    ON CONFLICT DO NOTHING""")
                    .param("athlete", ctx.athleteId())
                    .param("source", w.source())
                    .param("revision", revisionId(w.sourceId(), ctx.batchId()))
                    .param("sourceId", w.sourceId())
                    .param("day", w.calendarDate())
                    .param("rhr", w.restingHr())
                    .param("hrv", w.hrvRmssdMs())
                    .param("status", w.hrvStatus())
                    .param("resp", w.respirationRate())
                    .param("spo2", w.spo2Pct())
                    .param("stress", w.stressAvg())
                    .param("bbHigh", w.bodyBatteryHigh())
                    .param("bbLow", w.bodyBatteryLow())
                    .param("batch", ctx.batchId())
                    .update();
        }
        count(ctx, "daily_wellness", n);
    }

    /**
     * A sleep record. A Garmin night whose values differ from every stored version of it is
     * kept: under its own key when its start moved, otherwise as a revision (ADR 0010). The
     * readers (view sleep_session_current) use the newest import's version of each night. An
     * exported night that equals only synced versions is kept too (ADR 0015 D13): the values are
     * the same, but the Holdout must know the night now comes from an export.
     */
    public void sleep(Ctx ctx, Sleep s) {
        int n = jdbc.sql("""
                INSERT INTO sleep_session (athlete_id, source, source_id, start_at, end_at, cycle_date, is_nap,
                    deep_s, light_s, rem_s, awake_s, sleep_score, avg_respiration, avg_sleep_stress,
                    avg_spo2_pct, lowest_spo2_pct, import_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""")
                .params(ctx.athleteId(), s.source(), s.sourceId(), ts(s.startAt()), ts(s.endAt()), s.cycleDate(),
                        s.nap(), s.deepS(), s.lightS(), s.remS(), s.awakeS(), s.sleepScore(), s.avgRespiration(),
                        s.avgSleepStress(), s.avgSpo2Pct(), s.lowestSpo2Pct(), ctx.batchId())
                .update();
        if (n == 0 && RawRows.GARMIN.equals(s.source())) {
            n = jdbc.sql("""
                    INSERT INTO sleep_session (athlete_id, source, source_id, start_at, end_at, cycle_date, is_nap,
                        deep_s, light_s, rem_s, awake_s, sleep_score, avg_respiration, avg_sleep_stress,
                        avg_spo2_pct, lowest_spo2_pct, import_batch_id)
                    SELECT v.* FROM (VALUES (CAST(:athlete AS BIGINT), CAST(:source AS TEXT), CAST(:revision AS TEXT),
                        CAST(:start AS TIMESTAMPTZ), CAST(:end AS TIMESTAMPTZ), CAST(:day AS DATE), CAST(:nap AS BOOLEAN),
                        CAST(:deep AS INT), CAST(:light AS INT), CAST(:rem AS INT), CAST(:awake AS INT), CAST(:score AS INT),
                        CAST(:resp AS NUMERIC(4, 1)), CAST(:stress AS NUMERIC(5, 2)), CAST(:spo2 AS NUMERIC(4, 1)),
                        CAST(:lowSpo2 AS NUMERIC(4, 1)), CAST(:batch AS BIGINT)))
                        AS v (athlete_id, source, source_id, start_at, end_at, cycle_date, is_nap, deep_s, light_s, rem_s,
                              awake_s, sleep_score, avg_respiration, avg_sleep_stress, avg_spo2_pct, lowest_spo2_pct, import_batch_id)
                    WHERE NOT EXISTS (
                        SELECT 1 FROM sleep_session x
                        WHERE x.athlete_id = v.athlete_id AND x.source = v.source
                          AND split_part(x.source_id, '@', 1) = :sourceId
                          AND x.start_at = v.start_at
                          AND x.end_at = v.end_at
                          AND x.cycle_date = v.cycle_date
                          AND x.is_nap = v.is_nap
                          AND x.deep_s IS NOT DISTINCT FROM v.deep_s
                          AND x.light_s IS NOT DISTINCT FROM v.light_s
                          AND x.rem_s IS NOT DISTINCT FROM v.rem_s
                          AND x.awake_s IS NOT DISTINCT FROM v.awake_s
                          AND x.sleep_score IS NOT DISTINCT FROM v.sleep_score
                          AND x.avg_respiration IS NOT DISTINCT FROM v.avg_respiration
                          AND x.avg_sleep_stress IS NOT DISTINCT FROM v.avg_sleep_stress
                          AND x.avg_spo2_pct IS NOT DISTINCT FROM v.avg_spo2_pct
                          AND x.lowest_spo2_pct IS NOT DISTINCT FROM v.lowest_spo2_pct
                          -- An exported night equal to a synced version is still the export's: kept, so the
                          -- night is known to come from an export (ADR 0015 D13). Same values, same features.
                          AND NOT (
                              (SELECT kind FROM import_batch WHERE id = v.import_batch_id) = 'GARMIN_EXPORT'
                              AND (SELECT kind FROM import_batch WHERE id = x.import_batch_id) IS NOT DISTINCT FROM 'GARMIN_SYNC'))
                    ON CONFLICT DO NOTHING""")
                    .param("athlete", ctx.athleteId())
                    .param("source", s.source())
                    .param("revision", revisionId(s.sourceId(), ctx.batchId()))
                    .param("sourceId", s.sourceId())
                    .param("start", ts(s.startAt()))
                    .param("end", ts(s.endAt()))
                    .param("day", s.cycleDate())
                    .param("nap", s.nap())
                    .param("deep", s.deepS())
                    .param("light", s.lightS())
                    .param("rem", s.remS())
                    .param("awake", s.awakeS())
                    .param("score", s.sleepScore())
                    .param("resp", s.avgRespiration())
                    .param("stress", s.avgSleepStress())
                    .param("spo2", s.avgSpo2Pct())
                    .param("lowSpo2", s.lowestSpo2Pct())
                    .param("batch", ctx.batchId())
                    .update();
        }
        count(ctx, "sleep_session", n);
    }

    /** Whether a version of this Garmin night (source id = its calendarDate) came from a Garmin account export. */
    public boolean hasExportedSleep(long athleteId, String sleepSourceId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM sleep_session s JOIN import_batch b ON b.id = s.import_batch_id
                               WHERE s.athlete_id = ? AND s.source = 'GARMIN' AND split_part(s.source_id, '@', 1) = ?
                                 AND b.kind = 'GARMIN_EXPORT')""")
                .params(athleteId, sleepSourceId)
                .query(Boolean.class)
                .single();
    }

    /** Inserts the activity unless it exists; returns its id either way. */
    public long activity(Ctx ctx, Activity a) {
        Optional<Long> inserted = jdbc.sql("""
                INSERT INTO activity (athlete_id, source, source_id, sport, source_sport, start_at, calendar_date,
                    duration_s, distance_m, elevation_gain_m, avg_hr, max_hr, calories, training_load,
                    aerobic_te, anaerobic_te, whoop_strain, import_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id""")
                .params(ctx.athleteId(), a.source(), a.sourceId(), a.sport(), a.sourceSport(), ts(a.startAt()),
                        a.calendarDate(), a.durationS(), a.distanceM(), a.elevationGainM(), a.avgHr(), a.maxHr(),
                        a.calories(), a.trainingLoad(), a.aerobicTe(), a.anaerobicTe(), a.whoopStrain(), ctx.batchId())
                .query(Long.class)
                .optional();
        if (inserted.isPresent()) {
            ctx.stats().inserted("activity", 1);
            return inserted.get();
        }
        ctx.stats().duplicates("activity", 1);
        return jdbc.sql("SELECT id FROM activity WHERE athlete_id = ? AND source = ? AND source_id = ? AND start_at = ?")
                .params(ctx.athleteId(), a.source(), a.sourceId(), ts(a.startAt()))
                .query(Long.class)
                .single();
    }

    /**
     * Keeps the effort fields of a Garmin summary (ADR 0020 amendment), once per activity. A summary
     * that comes again, or that matched an activity already stored from a FIT file, fills in the
     * activity's row if it has none; an existing row is never changed.
     */
    public void activityEffort(Ctx ctx, long activityId, Integer workoutRpe, Integer workoutFeel) {
        if (workoutRpe == null && workoutFeel == null) return;
        int n = jdbc.sql("""
                INSERT INTO activity_effort (activity_id, workout_rpe, workout_feel, import_batch_id)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""")
                .params(activityId, workoutRpe, workoutFeel, ctx.batchId()).update();
        if (n > 0) ctx.stats().inserted("activity_effort", n);
        else ctx.stats().duplicates("activity_effort", 1);
    }

    /**
     * Finds an activity from {@code source} that started within {@code tolerance} of {@code start}.
     * Used to recognise the same workout arriving as a summary and as a FIT file.
     */
    public Optional<Long> activityNear(long athleteId, String source, Instant start, Duration tolerance) {
        return jdbc.sql("""
                SELECT id FROM activity
                WHERE athlete_id = ? AND source = ? AND start_at BETWEEN ? AND ?
                ORDER BY abs(extract(epoch FROM start_at - ?)) LIMIT 1""")
                .params(athleteId, source, ts(start.minus(tolerance)), ts(start.plus(tolerance)), ts(start))
                .query(Long.class)
                .optional();
    }

    /**
     * Writes per-second samples as multi-row INSERTs ({@value #STREAM_ROWS_PER_STATEMENT} rows per
     * statement). One round trip per chunk is ~10x faster than a JDBC batch of single-row inserts,
     * and unlike the driver's reWriteBatchedInserts it still returns an exact inserted count.
     */
    public void stream(Ctx ctx, long activityId, List<StreamPoint> points) {
        for (int from = 0; from < points.size(); from += STREAM_ROWS_PER_STATEMENT) {
            var chunk = points.subList(from, Math.min(points.size(), from + STREAM_ROWS_PER_STATEMENT));
            int inserted = template.update(streamInsertSql(chunk.size()), ps -> {
                int i = 1;
                for (StreamPoint p : chunk) {
                    ps.setLong(i++, activityId);
                    ps.setTimestamp(i++, ts(p.ts()));
                    ps.setObject(i++, p.hr(), java.sql.Types.SMALLINT);
                    ps.setObject(i++, p.speedMps(), java.sql.Types.REAL);
                    ps.setObject(i++, p.cadence(), java.sql.Types.SMALLINT);
                    ps.setObject(i++, p.power(), java.sql.Types.SMALLINT);
                    ps.setObject(i++, p.altitudeM(), java.sql.Types.REAL);
                    ps.setObject(i++, p.distanceM(), java.sql.Types.REAL);
                }
            });
            ctx.stats().inserted("activity_stream", inserted);
            ctx.stats().duplicates("activity_stream", chunk.size() - inserted);
        }
    }

    /** All-day HR samples, multi-row inserts like the activity stream. */
    public void monitoringHr(Ctx ctx, List<app.training.ingestion.garmin.FitMonitoringParser.Sample> samples) {
        for (int from = 0; from < samples.size(); from += STREAM_ROWS_PER_STATEMENT) {
            var chunk = samples.subList(from, Math.min(samples.size(), from + STREAM_ROWS_PER_STATEMENT));
            var sb = new StringBuilder("INSERT INTO monitoring_hr (athlete_id, ts, hr, import_batch_id) VALUES ");
            for (int r = 0; r < chunk.size(); r++) sb.append(r == 0 ? "" : ",").append("(?,?,?,?)");
            sb.append(" ON CONFLICT DO NOTHING");
            int inserted = template.update(sb.toString(), ps -> {
                int i = 1;
                for (var s : chunk) {
                    ps.setLong(i++, ctx.athleteId());
                    ps.setTimestamp(i++, ts(s.ts()));
                    ps.setInt(i++, s.hr());
                    ps.setLong(i++, ctx.batchId());
                }
            });
            ctx.stats().inserted("monitoring_hr", inserted);
            ctx.stats().duplicates("monitoring_hr", chunk.size() - inserted);
        }
    }

    private final java.util.Map<Integer, String> streamSqlCache = new java.util.concurrent.ConcurrentHashMap<>();

    private String streamInsertSql(int rows) {
        return streamSqlCache.computeIfAbsent(rows, n -> {
            var sb = new StringBuilder(
                    "INSERT INTO activity_stream (activity_id, ts, hr, speed_mps, cadence, power, altitude_m, distance_m) VALUES ");
            for (int r = 0; r < n; r++) sb.append(r == 0 ? "" : ",").append("(?,?,?,?,?,?,?,?)");
            return sb.append(" ON CONFLICT DO NOTHING").toString();
        });
    }

    public void whoopCycle(Ctx ctx, WhoopCycle c) {
        int n = jdbc.sql("""
                INSERT INTO whoop_reference (athlete_id, cycle_start, cycle_end, cycle_date, recovery_pct, day_strain,
                    hrv_rmssd_ms, resting_hr, skin_temp_c, spo2_pct, respiration_rate, sleep_performance_pct,
                    sleep_need_min, sleep_debt_min, asleep_min, energy_kcal, import_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""")
                .params(ctx.athleteId(), ts(c.cycleStart()), ts(c.cycleEnd()), c.cycleDate(), c.recoveryPct(),
                        c.dayStrain(), c.hrvRmssdMs(), c.restingHr(), c.skinTempC(), c.spo2Pct(), c.respirationRate(),
                        c.sleepPerformancePct(), c.sleepNeedMin(), c.sleepDebtMin(), c.asleepMin(), c.energyKcal(),
                        ctx.batchId())
                .update();
        count(ctx, "whoop_reference", n);
    }

    public void journal(Ctx ctx, JournalEntry j) {
        int n = jdbc.sql("""
                INSERT INTO whoop_journal_entry (athlete_id, cycle_start, cycle_date, question, answered_yes, notes, import_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""")
                .params(ctx.athleteId(), ts(j.cycleStart()), j.cycleDate(), j.question(), j.answeredYes(), j.notes(),
                        ctx.batchId())
                .update();
        count(ctx, "whoop_journal_entry", n);
    }
}
