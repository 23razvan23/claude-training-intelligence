package app.training.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ImportBatchRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ImportBatchRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** {@code userId} is null for a batch the app starts itself (a Garmin sync). */
    public long create(long athleteId, ImportKind kind, String filename, String sha256, Long userId) {
        return jdbc.sql("""
                INSERT INTO import_batch (athlete_id, kind, filename, file_sha256, status, created_by)
                VALUES (?, ?, ?, ?, 'PENDING', ?) RETURNING id""")
                .params(athleteId, kind.name(), filename, sha256, userId)
                .query(Long.class)
                .single();
    }

    public void markRunning(long id) {
        jdbc.sql("UPDATE import_batch SET status = 'RUNNING', started_at = now() WHERE id = ?").param(id).update();
    }

    /**
     * Writes progress. Callers run this in its own short transaction while the import's
     * transaction is open, so it must never wait on locks the import holds: lock_timeout
     * makes it fail fast instead (see ImportRunner.progressFor).
     */
    public void updateProgress(long id, ImportProgress p) {
        jdbc.sql("SET LOCAL lock_timeout = '1s'").update();
        var node = json.createObjectNode().put("phase", p.phase()).put("done", p.done());
        if (p.total() != null) node.put("total", p.total());
        jdbc.sql("UPDATE import_batch SET progress = CAST(? AS jsonb) WHERE id = ?").params(write(node), id).update();
    }

    public void markFinished(long id, ImportStats stats) {
        jdbc.sql("""
                UPDATE import_batch SET status = 'SUCCEEDED', stats = CAST(? AS jsonb), warnings = CAST(? AS jsonb),
                       finished_at = now() WHERE id = ?""")
                .params(write(stats.counters()), write(stats.warnings()), id)
                .update();
    }

    public void markFailed(long id, String error, ImportStats stats) {
        jdbc.sql("""
                UPDATE import_batch SET status = 'FAILED', error = ?, warnings = CAST(? AS jsonb), finished_at = now()
                WHERE id = ?""")
                .params(error, write(stats == null ? List.of() : stats.warnings()), id)
                .update();
    }

    /** Batches left PENDING/RUNNING by a crash or restart can never finish. */
    public int failInterrupted() {
        return jdbc.sql("""
                UPDATE import_batch SET status = 'FAILED', error = 'Interrupted by an application restart; re-run the import',
                       finished_at = now() WHERE status IN ('PENDING', 'RUNNING')""")
                .update();
    }

    public boolean hasActive(long athleteId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM import_batch WHERE athlete_id = ? AND status IN ('PENDING', 'RUNNING'))")
                .param(athleteId)
                .query(Boolean.class)
                .single();
    }

    public Optional<ImportBatch> find(long id) {
        return jdbc.sql("SELECT * FROM import_batch WHERE id = ?").param(id).query(this::map).optional();
    }

    public Optional<Long> previousSuccessWithHash(long athleteId, String sha256, long excludeId) {
        return jdbc.sql("""
                SELECT id FROM import_batch WHERE athlete_id = ? AND file_sha256 = ? AND status = 'SUCCEEDED' AND id <> ?
                ORDER BY id DESC LIMIT 1""")
                .params(athleteId, sha256, excludeId)
                .query(Long.class)
                .optional();
    }

    public List<ImportBatch> listForAthlete(long athleteId, int limit) {
        return jdbc.sql("SELECT * FROM import_batch WHERE athlete_id = ? ORDER BY id DESC LIMIT ?")
                .params(athleteId, limit)
                .query(this::map)
                .list();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ImportBatch map(ResultSet rs, int row) throws SQLException {
        try {
            var finished = rs.getTimestamp("finished_at");
            var started = rs.getTimestamp("started_at");
            var progress = rs.getString("progress");
            return new ImportBatch(
                    rs.getLong("id"),
                    rs.getLong("athlete_id"),
                    ImportKind.valueOf(rs.getString("kind")),
                    rs.getString("filename"),
                    rs.getString("status"),
                    json.readTree(rs.getString("stats")),
                    json.readTree(rs.getString("warnings")),
                    rs.getString("error"),
                    rs.getTimestamp("created_at").toInstant(),
                    started == null ? null : started.toInstant(),
                    finished == null ? null : finished.toInstant(),
                    progress == null ? null : json.readTree(progress));
        } catch (JsonProcessingException e) {
            throw new SQLException(e);
        }
    }
}
