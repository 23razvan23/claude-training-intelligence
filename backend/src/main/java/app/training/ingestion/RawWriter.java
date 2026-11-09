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
    private static final int STREAM_BATCH = 2_000;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public RawWriter(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public record Ctx(long athleteId, long batchId, ImportStats stats) {}

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static void count(Ctx ctx, String table, int updated) {
        if (updated > 0) ctx.stats().inserted(table, updated);
        else ctx.stats().duplicates(table, 1);
    }

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
        count(ctx, "daily_wellness", n);
    }

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
        count(ctx, "sleep_session", n);
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

    public void stream(Ctx ctx, long activityId, List<StreamPoint> points) {
        for (int from = 0; from < points.size(); from += STREAM_BATCH) {
            var chunk = points.subList(from, Math.min(points.size(), from + STREAM_BATCH));
            int[][] results = template.batchUpdate("""
                    INSERT INTO activity_stream (activity_id, ts, hr, speed_mps, cadence, power, altitude_m, distance_m)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING""", chunk, chunk.size(), (ps, p) -> {
                ps.setLong(1, activityId);
                ps.setTimestamp(2, ts(p.ts()));
                ps.setObject(3, p.hr());
                ps.setObject(4, p.speedMps());
                ps.setObject(5, p.cadence());
                ps.setObject(6, p.power());
                ps.setObject(7, p.altitudeM());
                ps.setObject(8, p.distanceM());
            });
            long inserted = 0;
            for (int[] batch : results) for (int r : batch) inserted += Math.max(r, 0);
            ctx.stats().inserted("activity_stream", inserted);
            ctx.stats().duplicates("activity_stream", chunk.size() - inserted);
        }
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
