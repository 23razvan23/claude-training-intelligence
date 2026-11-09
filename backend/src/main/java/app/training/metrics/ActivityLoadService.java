package app.training.metrics;

import app.training.athletes.Athlete;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Computes {@code activity_load} (Banister and Edwards TRIMP) for an athlete's Garmin
 * activities from their per-second HR, falling back to average HR × duration.
 */
@Service
public class ActivityLoadService {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final MetricsProperties props;

    public ActivityLoadService(JdbcClient jdbc, JdbcTemplate template, MetricsProperties props) {
        this.jdbc = jdbc;
        this.template = template;
        this.props = props;
    }

    public record Outcome(int computed, int fromStream, int fromAverage, int withoutLoad, Integer hrRest, boolean sexAssumed) {}

    /** HRrest: the profile value, else the median of Garmin daily resting HR. */
    Integer restingHr(Athlete a) {
        if (a.restingHr() != null) return a.restingHr();
        return jdbc.sql("""
                SELECT round(percentile_cont(0.5) WITHIN GROUP (ORDER BY resting_hr))::int
                FROM daily_wellness_merged WHERE athlete_id = ? AND source = 'GARMIN' AND resting_hr IS NOT NULL""")
                .param(a.id())
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    Trimp.Params params(Athlete a, Integer hrRest) {
        if (a.hrMax() == null || hrRest == null || a.hrMax() <= hrRest) return null;
        boolean female = "F".equals(a.sex());
        return new Trimp.Params(
                a.hrMax(), hrRest,
                female ? props.banisterFemaleK1() : props.banisterMaleK1(),
                female ? props.banisterFemaleK2() : props.banisterMaleK2(),
                props.edwardsZoneFloors(),
                props.maxSampleGapSeconds());
    }

    public Outcome recompute(Athlete athlete) {
        Integer hrRest = restingHr(athlete);
        Trimp.Params p = params(athlete, hrRest);
        boolean sexAssumed = athlete.sex() == null;

        record Act(long id, Integer durationS, Integer avgHr) {}
        List<Act> acts = jdbc.sql("SELECT id, duration_s, avg_hr FROM activity WHERE athlete_id = ? AND source = 'GARMIN' ORDER BY id")
                .param(athlete.id())
                .query((rs, i) -> new Act(rs.getLong("id"), (Integer) rs.getObject("duration_s"), (Integer) rs.getObject("avg_hr")))
                .list();

        jdbc.sql("DELETE FROM activity_load WHERE athlete_id = ?").param(athlete.id()).update();
        int stream = 0;
        int average = 0;
        int none = 0;
        var rows = new ArrayList<Object[]>();
        for (Act act : acts) {
            String method;
            Trimp.Result r = null;
            Double coverage = null;
            if (p == null) {
                method = "none";
            } else {
                var samples = new ArrayList<Trimp.Sample>();
                long[] t0 = {Long.MIN_VALUE};
                template.query("SELECT ts, hr FROM activity_stream WHERE activity_id = ? ORDER BY ts", rs -> {
                    long t = rs.getTimestamp(1).getTime() / 1000;
                    if (t0[0] == Long.MIN_VALUE) t0[0] = t;
                    samples.add(new Trimp.Sample(t - t0[0], rs.getObject(2, Integer.class)));
                }, act.id());
                Trimp.Result fromStream = samples.size() > 1 ? Trimp.fromStream(samples, p) : null;
                if (fromStream != null && fromStream.samples() > 0) {
                    r = fromStream;
                    method = "stream";
                    if (act.durationS() != null && act.durationS() > 0) {
                        coverage = Math.min(1.0, fromStream.coveredSeconds() / (double) act.durationS());
                    }
                } else if (act.avgHr() != null && act.durationS() != null && act.durationS() > 0) {
                    r = Trimp.fromAverage(act.avgHr(), act.durationS(), p);
                    method = "avg_hr";
                } else {
                    method = "none";
                }
            }
            switch (method) {
                case "stream" -> stream++;
                case "avg_hr" -> average++;
                default -> none++;
            }
            rows.add(new Object[] {
                act.id(), athlete.id(),
                r == null ? null : dec(r.banister(), 2), r == null ? null : dec(r.edwards(), 2),
                r == null ? null : r.samples(), coverage == null ? null : dec(coverage, 3),
                method, athlete.hrMax(), hrRest, sexAssumed, props.featureVersion()});
        }
        template.batchUpdate("""
                INSERT INTO activity_load (activity_id, athlete_id, trimp_banister, trimp_edwards, hr_samples, hr_coverage,
                    method, hr_max, hr_rest, sex_assumed, feature_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        return new Outcome(acts.size(), stream, average, none, hrRest, sexAssumed);
    }

    private static BigDecimal dec(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
