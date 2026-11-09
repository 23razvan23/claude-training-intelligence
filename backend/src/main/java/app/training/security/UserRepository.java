package app.training.security;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CurrentUser> findByUsername(String username) {
        return jdbc.sql("SELECT * FROM app_user WHERE lower(username) = lower(?)")
                .param(username)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<CurrentUser> findById(long id) {
        return jdbc.sql("SELECT * FROM app_user WHERE id = ?").param(id).query(UserRepository::map).optional();
    }

    public List<CurrentUser> findAll() {
        return jdbc.sql("SELECT * FROM app_user ORDER BY role, username").query(UserRepository::map).list();
    }

    public boolean anyCoach() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE role = 'COACH')").query(Boolean.class).single();
    }

    public long insert(String username, String passwordHash, Role role, Long athleteId) {
        return jdbc.sql("""
                INSERT INTO app_user (username, password_hash, role, athlete_id)
                VALUES (?, ?, ?, ?) RETURNING id""")
                .params(username, passwordHash, role.name(), athleteId)
                .query(Long.class)
                .single();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM app_user WHERE id = ? AND role = 'ATHLETE'").param(id).update() == 1;
    }

    private static CurrentUser map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new CurrentUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                (Long) rs.getObject("athlete_id"),
                rs.getBoolean("enabled"));
    }
}
