package app.training.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Builds {@code day_features}: one row per physiological-cycle day with the Garmin
 * inputs, training load, personal baselines and (as targets only) Whoop's scores.
 * This table is the single feature source for calibration and daily scoring (ADR 0007).
 */
@Service
public class DayFeatureService {
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final MetricsProperties props;

    public DayFeatureService(JdbcClient jdbc, JdbcTemplate template, MetricsProperties props) {
        this.jdbc = jdbc;
        this.template = template;
        this.props = props;
    }

    public record Outcome(int days, int withGarminCore, int withWhoop, int paired, LocalDate from, LocalDate to) {}

    private record Wellness(Integer rhr, Double rmssd, Integer bodyBattery) {}

    private record Sleep(Integer minutes, Integer deep, Integer rem, Integer light, Integer score, Double resp, Double spo2) {}

    private record Load(Double banister, Double edwards, double runKm, int count, int missing) {}

    private record Whoop(Integer recovery, Double strain, Integer need, Integer perf, Integer asleep, Integer debt) {}

    private static Integer i(ResultSet rs, String c) throws SQLException {
        Object o = rs.getObject(c);
        return o == null ? null : ((Number) o).intValue();
    }

    private static Double d(ResultSet rs, String c) throws SQLException {
        Object o = rs.getObject(c);
        return o == null ? null : ((Number) o).doubleValue();
    }

    private record Background(double load, long coveredSeconds) {}

    /**
     * Background load per cycle day from all-day monitoring HR: Banister-weighted minutes
     * outside Garmin activity windows (activities already carry their own load). Samples
     * belong to the cycle opened by the last main sleep that ended before them, like
     * activities (ADR 0002). Returns load and covered seconds (including activity time).
     */
    Map<LocalDate, Background> backgroundLoad(long athleteId, Trimp.Params params, ZoneId zone) {
        var windows = new ArrayList<long[]>();
        template.query("""
                SELECT extract(epoch FROM start_at)::bigint AS s, duration_s FROM activity
                WHERE athlete_id = ? AND source = 'GARMIN' AND duration_s > 0 ORDER BY start_at""",
                rs -> {
                    long st = rs.getLong("s");
                    long en = st + rs.getLong("duration_s");
                    if (!windows.isEmpty() && st <= windows.getLast()[1]) windows.getLast()[1] = Math.max(windows.getLast()[1], en);
                    else windows.add(new long[] {st, en});
                }, athleteId);
        var sleepEnds = new ArrayList<Object[]>();
        template.query("""
                SELECT extract(epoch FROM end_at)::bigint AS e, cycle_date FROM sleep_session
                WHERE athlete_id = ? AND source = 'GARMIN' AND NOT is_nap ORDER BY end_at""",
                rs -> {
                    sleepEnds.add(new Object[] {rs.getLong("e"), rs.getObject("cycle_date", LocalDate.class)});
                }, athleteId);

        var acc = new HashMap<LocalDate, double[]>();
        var streaming = new JdbcTemplate(java.util.Objects.requireNonNull(template.getDataSource()));
        streaming.setFetchSize(20_000); // stream inside the recompute transaction instead of loading millions of rows
        long maxGap = props.monitoringMaxGapSeconds();
        long[] prevT = {Long.MIN_VALUE};
        int[] prevHr = {0};
        int[] w = {0};
        int[] sl = {0};
        streaming.query("SELECT extract(epoch FROM ts)::bigint AS t, hr FROM monitoring_hr WHERE athlete_id = ? ORDER BY ts", rs -> {
            long t = rs.getLong("t");
            int hr = rs.getInt("hr");
            long p = prevT[0];
            if (p != Long.MIN_VALUE && t - p > 0 && t - p <= maxGap) {
                // cycle of the interval start: last main sleep ending at or before it, within 36 h
                while (sl[0] + 1 < sleepEnds.size() && (long) sleepEnds.get(sl[0] + 1)[0] <= p) sl[0]++;
                LocalDate day = null;
                if (!sleepEnds.isEmpty()) {
                    long end = (long) sleepEnds.get(sl[0])[0];
                    if (end <= p && p - end < 36 * 3600) day = (LocalDate) sleepEnds.get(sl[0])[1];
                }
                if (day == null) day = LocalDate.ofInstant(java.time.Instant.ofEpochSecond(p), zone);
                while (w[0] < windows.size() && windows.get(w[0])[1] <= p) w[0]++;
                boolean inActivity = w[0] < windows.size() && windows.get(w[0])[0] <= p;
                double[] a = acc.computeIfAbsent(day, k -> new double[2]);
                a[1] += t - p;
                if (!inActivity && params != null) a[0] += (t - p) / 60.0 * Trimp.banisterWeight(prevHr[0], params);
            }
            prevT[0] = t;
            prevHr[0] = hr;
        }, athleteId);

        var out = new HashMap<LocalDate, Background>();
        acc.forEach((day, a) -> out.put(day, new Background(params == null ? Double.NaN : a[0], (long) a[1])));
        return out;
    }

    /** Recomputes every day with any Garmin or Whoop data for the athlete. Idempotent. */
    public Outcome recompute(long athleteId, Trimp.Params params, ZoneId zone) {
        var wellness = new HashMap<LocalDate, Wellness>();
        template.query("""
                SELECT calendar_date, resting_hr, hrv_rmssd_ms, body_battery_high FROM daily_wellness_merged
                WHERE athlete_id = ? AND source = 'GARMIN'""", rs -> {
            wellness.put(rs.getObject("calendar_date", LocalDate.class),
                    new Wellness(i(rs, "resting_hr"), d(rs, "hrv_rmssd_ms"), i(rs, "body_battery_high")));
        }, athleteId);

        var sleep = new HashMap<LocalDate, Sleep>();
        template.query("""
                SELECT cycle_date,
                       sum(coalesce(deep_s, 0) + coalesce(light_s, 0) + coalesce(rem_s, 0)) / 60 AS minutes,
                       sum(deep_s) / 60 AS deep, sum(rem_s) / 60 AS rem, sum(light_s) / 60 AS light,
                       max(sleep_score) AS score, avg(avg_respiration) AS resp, avg(avg_spo2_pct) AS spo2
                FROM sleep_session WHERE athlete_id = ? AND source = 'GARMIN' AND NOT is_nap
                GROUP BY cycle_date""", rs -> {
            sleep.put(rs.getObject("cycle_date", LocalDate.class), new Sleep(
                    i(rs, "minutes"), i(rs, "deep"), i(rs, "rem"), i(rs, "light"), i(rs, "score"), d(rs, "resp"), d(rs, "spo2")));
        }, athleteId);

        var load = new HashMap<LocalDate, Load>();
        template.query("""
                SELECT ac.cycle_date,
                       sum(l.trimp_banister) AS banister, sum(l.trimp_edwards) AS edwards,
                       coalesce(sum(ac.distance_m) FILTER (WHERE ac.sport = 'running'), 0) / 1000.0 AS run_km,
                       count(*) AS n,
                       count(*) FILTER (WHERE l.trimp_banister IS NULL) AS missing
                FROM activity_with_cycle ac
                LEFT JOIN activity_load l ON l.activity_id = ac.id
                WHERE ac.athlete_id = ? AND ac.source = 'GARMIN'
                GROUP BY ac.cycle_date""", rs -> {
            load.put(rs.getObject("cycle_date", LocalDate.class), new Load(
                    d(rs, "banister"), d(rs, "edwards"), rs.getDouble("run_km"), rs.getInt("n"), rs.getInt("missing")));
        }, athleteId);

        var whoop = new HashMap<LocalDate, Whoop>();
        template.query("""
                SELECT DISTINCT ON (cycle_date) cycle_date, recovery_pct, day_strain, sleep_need_min,
                       sleep_performance_pct, asleep_min, sleep_debt_min
                FROM whoop_reference WHERE athlete_id = ? ORDER BY cycle_date, cycle_start""", rs -> {
            whoop.put(rs.getObject("cycle_date", LocalDate.class), new Whoop(
                    i(rs, "recovery_pct"), d(rs, "day_strain"), i(rs, "sleep_need_min"),
                    i(rs, "sleep_performance_pct"), i(rs, "asleep_min"), i(rs, "sleep_debt_min")));
        }, athleteId);

        var background = backgroundLoad(athleteId, params, zone);

        var days = new TreeSet<LocalDate>();
        days.addAll(background.keySet());
        days.addAll(wellness.keySet());
        days.addAll(sleep.keySet());
        days.addAll(load.keySet());
        days.addAll(whoop.keySet());

        var lnRmssd = new TreeMap<LocalDate, Double>();
        var rhr = new TreeMap<LocalDate, Double>();
        var resp = new TreeMap<LocalDate, Double>();
        for (LocalDate day : days) {
            Wellness w = wellness.get(day);
            if (w != null && w.rmssd() != null && w.rmssd() > 0) lnRmssd.put(day, Math.log(w.rmssd()));
            if (w != null && w.rhr() != null) rhr.put(day, w.rhr().doubleValue());
            Sleep s = sleep.get(day);
            if (s != null && s.resp() != null) resp.put(day, s.resp());
        }

        int window = props.baselineWindowDays();
        int min = props.baselineMinDays();
        var rows = new ArrayList<Object[]>();
        int core = 0;
        int withWhoop = 0;
        int paired = 0;
        for (LocalDate day : days) {
            Wellness w = wellness.getOrDefault(day, new Wellness(null, null, null));
            Sleep s = sleep.get(day);
            Load l = load.get(day);
            Whoop wh = whoop.get(day);
            Background bg = background.get(day);

            Double ln = lnRmssd.get(day);
            var hrvBase = Baseline.of(lnRmssd, day, window, min);
            var rhrBase = Baseline.of(rhr, day, window, min);
            var respBase = Baseline.of(resp, day, window, min);
            Double z = ln != null && hrvBase != null ? hrvBase.z(ln) : null;
            Double rhrDev = w.rhr() != null && rhrBase != null ? w.rhr() - rhrBase.mean() : null;
            Double respDev = s != null && s.resp() != null && respBase != null ? s.resp() - respBase.mean() : null;

            boolean hasCore = ln != null && w.rhr() != null && s != null && s.minutes() != null && s.minutes() > 0;
            boolean hasWhoop = wh != null && wh.recovery() != null && wh.strain() != null;
            if (hasCore) core++;
            if (hasWhoop) withWhoop++;
            if (hasCore && hasWhoop) paired++;

            // A day with Garmin data but no activities has zero training load, not unknown load.
            boolean garminDay = wellness.containsKey(day) || s != null || l != null;
            Double banister = l != null && l.banister() != null ? l.banister() : (garminDay && (l == null || l.missing() == 0) ? 0.0 : null);
            Double edwards = l != null && l.edwards() != null ? l.edwards() : (garminDay && (l == null || l.missing() == 0) ? 0.0 : null);

            rows.add(new Object[] {
                athleteId, day, props.featureVersion(),
                dec(w.rmssd(), 1), dec(ln, 4), w.rhr(),
                s == null ? null : dec(s.resp(), 1), s == null ? null : dec(s.spo2(), 1), w.bodyBattery(),
                s == null ? null : s.minutes(), s == null ? null : s.deep(), s == null ? null : s.rem(),
                s == null ? null : s.light(), s == null ? null : s.score(),
                dec(banister, 2), dec(edwards, 2), l == null ? BigDecimal.ZERO.setScale(2) : dec(l.runKm(), 2),
                l == null ? 0 : l.count(), l == null ? 0 : l.missing(),
                hrvBase == null ? null : hrvBase.n(), dec(z, 3), dec(rhrDev, 2), dec(respDev, 2),
                hasCore,
                wh == null ? null : wh.recovery(), wh == null ? null : dec(wh.strain(), 1),
                wh == null ? null : wh.need(), wh == null ? null : wh.perf(),
                wh == null ? null : wh.asleep(), wh == null ? null : wh.debt(),
                bg == null || Double.isNaN(bg.load()) ? null : dec(bg.load(), 2),
                bg == null ? null : (int) (bg.coveredSeconds() / 60)});
        }

        jdbc.sql("DELETE FROM day_features WHERE athlete_id = ? AND feature_version = ?")
                .params(athleteId, props.featureVersion())
                .update();
        template.batchUpdate("""
                INSERT INTO day_features (athlete_id, cycle_date, feature_version,
                    rmssd, ln_rmssd, rhr, sleep_respiration, sleep_spo2, body_battery_high,
                    sleep_minutes, deep_minutes, rem_minutes, light_minutes, garmin_sleep_score,
                    day_load_banister, day_load_edwards, run_km, activity_count, load_missing_activities,
                    baseline_days, ln_rmssd_z, rhr_dev, resp_dev, has_garmin_core,
                    whoop_recovery, whoop_strain, whoop_sleep_need_min, whoop_sleep_perf, whoop_asleep_min, whoop_sleep_debt_min,
                    background_load, monitored_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);

        return new Outcome(days.size(), core, withWhoop, paired,
                days.isEmpty() ? null : days.first(), days.isEmpty() ? null : days.last());
    }

    private static BigDecimal dec(Double v, int scale) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
