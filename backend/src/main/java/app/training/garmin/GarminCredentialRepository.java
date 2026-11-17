package app.training.garmin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The garmin_credential table (V19, ADR 0011 D3): ciphertext only, one row per athlete. */
@Repository
public class GarminCredentialRepository {
    public record Row(long athleteId, byte[] tokenCiphertext, String keyId, String status,
                      Instant connectedAt, Instant tokenRefreshedAt, String lastError) {
        @Override
        public String toString() {
            return "Row[athleteId=" + athleteId + ", keyId=" + keyId + ", status=" + status + "]";
        }
    }

    private final JdbcClient jdbc;

    public GarminCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(long athleteId) {
        return jdbc.sql("""
                SELECT athlete_id, token_ciphertext, key_id, status, connected_at, token_refreshed_at, last_error
                FROM garmin_credential WHERE athlete_id = ?""")
                .param(athleteId)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getBytes(2), rs.getString(3), rs.getString(4),
                        instant(rs.getObject(5, OffsetDateTime.class)), instant(rs.getObject(6, OffsetDateTime.class)),
                        rs.getString(7)))
                .optional();
    }

    /** Athletes whose Garmin is connected (the morning run looks at these only). */
    public java.util.List<Long> connectedAthletes() {
        return jdbc.sql("SELECT athlete_id FROM garmin_credential WHERE status = 'CONNECTED' ORDER BY athlete_id")
                .query(Long.class).list();
    }

    /** A new Connect replaces any earlier row, including one locked with an old key (ADR 0011 D7). */
    public void saveConnected(long athleteId, byte[] ciphertext, String keyId, Instant now) {
        jdbc.sql("""
                INSERT INTO garmin_credential (athlete_id, token_ciphertext, key_id, status, connected_at,
                                               token_refreshed_at, last_error, updated_at)
                VALUES (:a, :c, :k, 'CONNECTED', :now, NULL, NULL, :now)
                ON CONFLICT (athlete_id) DO UPDATE SET token_ciphertext = EXCLUDED.token_ciphertext,
                    key_id = EXCLUDED.key_id, format_version = 1, status = 'CONNECTED',
                    connected_at = EXCLUDED.connected_at, token_refreshed_at = NULL, last_error = NULL,
                    updated_at = EXCLUDED.updated_at""")
                .param("a", athleteId).param("c", ciphertext).param("k", keyId).param("now", ts(now))
                .update();
    }

    /** Garmin refreshed the token during a call: store the new bundle (ADR 0011 D4, D6). */
    public void saveRefreshed(long athleteId, byte[] ciphertext, String keyId, Instant now) {
        jdbc.sql("""
                UPDATE garmin_credential SET token_ciphertext = :c, key_id = :k, token_refreshed_at = :now, updated_at = :now
                WHERE athlete_id = :a""")
                .param("a", athleteId).param("c", ciphertext).param("k", keyId).param("now", ts(now))
                .update();
    }

    /** Garmin rejected the token, or it no longer decrypts: stop syncing until a new Connect (D6, D7). */
    public void markReconnectNeeded(long athleteId, String errorCode, Instant now) {
        jdbc.sql("""
                UPDATE garmin_credential SET status = 'RECONNECT_NEEDED', last_error = :e, updated_at = :now
                WHERE athlete_id = :a""")
                .param("a", athleteId).param("e", errorCode).param("now", ts(now))
                .update();
    }

    /** Disconnect (ADR 0011 D7). */
    public boolean delete(long athleteId) {
        return jdbc.sql("DELETE FROM garmin_credential WHERE athlete_id = ?").param(athleteId).update() > 0;
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
