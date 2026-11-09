package app.training.athletes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AthleteRepository {
    public static final String DEFAULT_TIMEZONE = "Europe/Bucharest";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AthleteRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Athlete> findAll() {
        return jdbc.sql("SELECT * FROM athlete ORDER BY name").query(this::map).list();
    }

    public Optional<Athlete> findById(long id) {
        return jdbc.sql("SELECT * FROM athlete WHERE id = ?").param(id).query(this::map).optional();
    }

    public ZoneId zoneOf(long athleteId) {
        return findById(athleteId).map(a -> ZoneId.of(a.timezone())).orElse(ZoneId.of(DEFAULT_TIMEZONE));
    }

    public long insert(AthleteRequest r) {
        return jdbc.sql("""
                INSERT INTO athlete (name, sex, birth_year, hr_max, resting_hr, lthr, threshold_pace_s_per_km,
                                     hr_zones, pace_zones, timezone, injury_notes)
                VALUES (:name, :sex, :birthYear, :hrMax, :restingHr, :lthr, :pace,
                        CAST(:hrZones AS jsonb), CAST(:paceZones AS jsonb), :tz, :injury)
                RETURNING id""")
                .params(params(r))
                .query(Long.class)
                .single();
    }

    public boolean update(long id, AthleteRequest r) {
        var p = params(r);
        p.put("id", id);
        return jdbc.sql("""
                UPDATE athlete SET name = :name, sex = :sex, birth_year = :birthYear, hr_max = :hrMax,
                       resting_hr = :restingHr, lthr = :lthr, threshold_pace_s_per_km = :pace,
                       hr_zones = CAST(:hrZones AS jsonb), pace_zones = CAST(:paceZones AS jsonb),
                       timezone = :tz, injury_notes = :injury, updated_at = now()
                WHERE id = :id""")
                .params(p)
                .update() == 1;
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM athlete WHERE id = ?").param(id).update() == 1;
    }

    private java.util.Map<String, Object> params(AthleteRequest r) {
        var p = new java.util.HashMap<String, Object>();
        p.put("name", r.name().trim());
        p.put("sex", r.sex());
        p.put("birthYear", r.birthYear());
        p.put("hrMax", r.hrMax());
        p.put("restingHr", r.restingHr());
        p.put("lthr", r.lthr());
        p.put("pace", r.thresholdPaceSPerKm());
        p.put("hrZones", toJson(r.hrZones()));
        p.put("paceZones", toJson(r.paceZones()));
        p.put("tz", r.timezone() == null || r.timezone().isBlank() ? DEFAULT_TIMEZONE : r.timezone());
        p.put("injury", r.injuryNotes());
        return p;
    }

    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private JsonNode fromJson(String s) throws SQLException {
        if (s == null) return null;
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            throw new SQLException("Bad JSON in athlete row", e);
        }
    }

    private Athlete map(ResultSet rs, int row) throws SQLException {
        return new Athlete(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("sex"),
                (Integer) rs.getObject("birth_year"),
                (Integer) rs.getObject("hr_max"),
                (Integer) rs.getObject("resting_hr"),
                (Integer) rs.getObject("lthr"),
                (Integer) rs.getObject("threshold_pace_s_per_km"),
                fromJson(rs.getString("hr_zones")),
                fromJson(rs.getString("pace_zones")),
                rs.getString("timezone"),
                rs.getString("injury_notes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
