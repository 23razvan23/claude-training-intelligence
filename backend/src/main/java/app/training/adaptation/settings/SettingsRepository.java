package app.training.adaptation.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** settings_version is append-only (ADR 0018 D2: a trigger refuses UPDATE and DELETE); this class only inserts and reads. */
@Repository
public class SettingsRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public SettingsRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The current version: the newest one. */
    public Optional<AppSettings> newest() {
        return jdbc.sql("SELECT id, settings::text AS settings, note, created_at FROM settings_version ORDER BY id DESC LIMIT 1")
                .query(this::map).optional();
    }

    public Optional<AppSettings> find(long id) {
        return jdbc.sql("SELECT id, settings::text AS settings, note, created_at FROM settings_version WHERE id = ?")
                .param(id).query(this::map).optional();
    }

    public List<AppSettings> all() {
        return jdbc.sql("SELECT id, settings::text AS settings, note, created_at FROM settings_version ORDER BY id DESC")
                .query(this::map).list();
    }

    public long insert(JsonNode settings, String note, Long userId) {
        try {
            return jdbc.sql("INSERT INTO settings_version (settings, note, created_by) VALUES (?::jsonb, ?, ?) RETURNING id")
                    .params(json.writeValueAsString(settings), note, userId).query(Long.class).single();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serialises writers of a new version within the current transaction, so two starts can't both add one. */
    public void lockForWrite() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('settings_version'))").query().singleRow();
    }

    private AppSettings map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            return new AppSettings(rs.getLong("id"), json.readTree(rs.getString("settings")), rs.getString("note"),
                    rs.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("settings_version " + rs.getLong("id") + " is not valid JSON", e);
        }
    }
}
