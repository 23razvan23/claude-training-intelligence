package app.training.garmin;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The garmin_sync_run table (V20, ADR 0015 D6): one row per sync run, no Garmin data, no secret. */
@Repository
public class GarminSyncRunRepository {
    public enum Trigger { MANUAL, MORNING }

    public enum Status { RUNNING, RETRY_WAIT, SUCCEEDED, FAILED }

    /** What the UI shows about a run. {@code missing} lists parts Garmin had no data for, e.g. "hrv:2026-11-17". */
    public record Run(long id, long athleteId, Trigger trigger, Status status, int attempt, LocalDate firstDay, LocalDate lastDay,
                      Instant startedAt, Instant finishedAt, Instant nextRetryAt, String errorCode, Long importBatchId,
                      Long rowsInserted, boolean recomputed, List<String> missing) {}

    private final JdbcClient jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public GarminSyncRunRepository(JdbcClient jdbc, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private final RowMapper<Run> mapper = (rs, n) -> new Run(
            rs.getLong("id"), rs.getLong("athlete_id"), Trigger.valueOf(rs.getString("trigger")),
            Status.valueOf(rs.getString("status")), rs.getInt("attempt"),
            rs.getObject("first_day", LocalDate.class), rs.getObject("last_day", LocalDate.class),
            instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
            instant(rs.getTimestamp("next_retry_at")), rs.getString("error_code"),
            (Long) rs.getObject("import_batch_id"), (Long) rs.getObject("rows_inserted"), rs.getBoolean("recomputed"),
            missing(rs.getString("missing")));

    /**
     * Starts a run; empty when the athlete already has one running or waiting to retry (the
     * partial unique index decides, so two clicks or a click and the morning run cannot both start).
     */
    public Optional<Long> start(long athleteId, Trigger trigger, LocalDate firstDay, LocalDate lastDay, Instant now) {
        return jdbc.sql("""
                INSERT INTO garmin_sync_run (athlete_id, trigger, status, first_day, last_day, started_at)
                VALUES (?, ?, 'RUNNING', ?, ?, ?)
                ON CONFLICT (athlete_id) WHERE status IN ('RUNNING', 'RETRY_WAIT') DO NOTHING
                RETURNING id""")
                .params(athleteId, trigger.name(), firstDay, lastDay, ts(now))
                .query(Long.class)
                .optional();
    }

    public Optional<Run> find(long id) {
        return jdbc.sql("SELECT * FROM garmin_sync_run WHERE id = ?").param(id).query(mapper).optional();
    }

    public List<Run> recent(long athleteId, int limit) {
        return jdbc.sql("SELECT * FROM garmin_sync_run WHERE athlete_id = ? ORDER BY started_at DESC, id DESC LIMIT ?")
                .params(athleteId, limit).query(mapper).list();
    }

    public Optional<Run> active(long athleteId) {
        return jdbc.sql("SELECT * FROM garmin_sync_run WHERE athlete_id = ? AND status IN ('RUNNING', 'RETRY_WAIT')")
                .param(athleteId).query(mapper).optional();
    }

    public void succeed(long id, Long batchId, long rowsInserted, boolean recomputed, List<String> missing, Instant now) {
        jdbc.sql("""
                UPDATE garmin_sync_run SET status = 'SUCCEEDED', finished_at = ?, next_retry_at = NULL, error_code = NULL,
                    import_batch_id = ?, rows_inserted = ?, recomputed = ?, missing = ?::jsonb
                WHERE id = ?""")
                .params(ts(now), batchId, rowsInserted, recomputed, toJson(missing), id).update();
    }

    /** {@code batchId} and {@code rowsInserted} are kept when the import succeeded but a later step failed. */
    public void fail(long id, String errorCode, Long batchId, Long rowsInserted, Instant now) {
        jdbc.sql("""
                UPDATE garmin_sync_run SET status = 'FAILED', finished_at = ?, next_retry_at = NULL, error_code = ?,
                    import_batch_id = coalesce(?, import_batch_id), rows_inserted = coalesce(?, rows_inserted)
                WHERE id = ?""")
                .params(ts(now), errorCode, batchId, rowsInserted, id).update();
    }

    public void retryLater(long id, String errorCode, Instant at) {
        jdbc.sql("UPDATE garmin_sync_run SET status = 'RETRY_WAIT', error_code = ?, next_retry_at = ? WHERE id = ?")
                .params(errorCode, ts(at), id).update();
    }

    /** Claims a waiting run whose retry time has come; false if another poller or a Sync now got it first. */
    public boolean claimRetry(long id, Instant now) {
        return jdbc.sql("""
                UPDATE garmin_sync_run SET status = 'RUNNING', attempt = attempt + 1, next_retry_at = NULL
                WHERE id = ? AND status = 'RETRY_WAIT' AND next_retry_at <= ?""")
                .params(id, ts(now)).update() == 1;
    }

    public List<Long> dueRetries(Instant now) {
        return jdbc.sql("SELECT id FROM garmin_sync_run WHERE status = 'RETRY_WAIT' AND next_retry_at <= ? ORDER BY next_retry_at")
                .param(ts(now)).query(Long.class).list();
    }

    /** A Sync now replaces a run that is waiting to retry. */
    public void supersedeWaiting(long athleteId, Instant now) {
        jdbc.sql("""
                UPDATE garmin_sync_run SET status = 'FAILED', finished_at = ?, next_retry_at = NULL
                WHERE athlete_id = ? AND status = 'RETRY_WAIT'""")
                .params(ts(now), athleteId).update();
    }

    /** Runs left RUNNING by a restart of the app (like interrupted imports, ADR 0006). */
    public int failInterrupted(Instant now) {
        return jdbc.sql("""
                UPDATE garmin_sync_run SET status = 'FAILED', finished_at = ?, error_code = 'INTERRUPTED'
                WHERE status = 'RUNNING'""")
                .param(ts(now)).update();
    }

    private String toJson(List<String> missing) {
        try {
            return missing == null || missing.isEmpty() ? null : json.writeValueAsString(missing);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> missing(String raw) {
        if (raw == null) return List.of();
        try {
            return List.of(json.readValue(raw, String[].class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
