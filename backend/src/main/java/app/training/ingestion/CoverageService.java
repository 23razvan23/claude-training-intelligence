package app.training.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Data-coverage report: for each physiological-cycle day, which Garmin and Whoop
 * inputs exist. "Paired" days (complete Garmin AND complete Whoop) are the days
 * Phase 1 can calibrate on.
 *
 * <p>Garmin complete = resting HR + overnight HRV + a main sleep ending that day.
 * Whoop complete = recovery score + day strain.
 */
@Service
public class CoverageService {
    static final int MAX_DAYS = 3 * 366;

    private final JdbcClient jdbc;

    public CoverageService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Day(
            LocalDate date, boolean garminRestingHr, boolean garminHrv, boolean garminSleep, int garminActivities,
            boolean whoopRecovery, boolean whoopStrain, boolean whoopSleep) {
        @JsonProperty
        public boolean garminComplete() {
            return garminRestingHr && garminHrv && garminSleep;
        }

        @JsonProperty
        public boolean whoopComplete() {
            return whoopRecovery && whoopStrain;
        }

        @JsonProperty
        public boolean paired() {
            return garminComplete() && whoopComplete();
        }
    }

    public record Gap(LocalDate from, LocalDate to, long days) {}

    public record Report(
            LocalDate from, LocalDate to, long totalDays, long garminCompleteDays, long whoopCompleteDays,
            long pairedDays, Map<String, Long> fieldDays, List<Gap> pairedGaps, List<Day> days) {}

    public Report report(long athleteId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            var bounds = jdbc.sql("""
                    SELECT min(d) AS lo, max(d) AS hi FROM (
                        SELECT cycle_date AS d FROM whoop_reference WHERE athlete_id = :a
                        UNION ALL SELECT calendar_date FROM daily_wellness WHERE athlete_id = :a AND source = 'GARMIN'
                        UNION ALL SELECT cycle_date FROM sleep_session WHERE athlete_id = :a AND source = 'GARMIN'
                    ) x""")
                    .param("a", athleteId)
                    .query((rs, i) -> new LocalDate[] {
                        rs.getObject("lo", LocalDate.class), rs.getObject("hi", LocalDate.class)})
                    .single();
            if (from == null) from = bounds[0];
            if (to == null) to = bounds[1];
        }
        if (from == null || to == null) {
            return new Report(null, null, 0, 0, 0, 0, Map.of(), List.of(), List.of());
        }
        if (to.isBefore(from)) throw new app.training.common.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "'to' is before 'from'");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) from = to.minusDays(MAX_DAYS - 1);

        List<Day> days = jdbc.sql("""
                SELECT d::date AS day,
                       gw.resting_hr IS NOT NULL   AS g_rhr,
                       gw.hrv_rmssd_ms IS NOT NULL AS g_hrv,
                       EXISTS (SELECT 1 FROM sleep_session s
                               WHERE s.athlete_id = :a AND s.source = 'GARMIN' AND NOT s.is_nap AND s.cycle_date = d::date) AS g_sleep,
                       (SELECT count(*) FROM activity_with_cycle ac
                        WHERE ac.athlete_id = :a AND ac.source = 'GARMIN' AND ac.cycle_date = d::date) AS g_acts,
                       w.recovery_pct IS NOT NULL AS w_rec,
                       w.day_strain IS NOT NULL AS w_strain,
                       w.sleep_performance_pct IS NOT NULL AS w_sleep
                FROM generate_series(CAST(:from AS date), CAST(:to AS date), INTERVAL '1 day') d
                LEFT JOIN daily_wellness_merged gw
                       ON gw.athlete_id = :a AND gw.source = 'GARMIN' AND gw.calendar_date = d::date
                LEFT JOIN LATERAL (
                    SELECT * FROM whoop_reference wr WHERE wr.athlete_id = :a AND wr.cycle_date = d::date
                    ORDER BY wr.cycle_start LIMIT 1) w ON TRUE
                ORDER BY day""")
                .param("a", athleteId)
                .param("from", from)
                .param("to", to)
                .query((rs, i) -> new Day(
                        rs.getObject("day", LocalDate.class),
                        rs.getBoolean("g_rhr"),
                        rs.getBoolean("g_hrv"),
                        rs.getBoolean("g_sleep"),
                        rs.getInt("g_acts"),
                        rs.getBoolean("w_rec"),
                        rs.getBoolean("w_strain"),
                        rs.getBoolean("w_sleep")))
                .list();

        var fields = new LinkedHashMap<String, Long>();
        fields.put("garminRestingHr", days.stream().filter(Day::garminRestingHr).count());
        fields.put("garminHrv", days.stream().filter(Day::garminHrv).count());
        fields.put("garminSleep", days.stream().filter(Day::garminSleep).count());
        fields.put("garminActivityDays", days.stream().filter(d -> d.garminActivities() > 0).count());
        fields.put("whoopRecovery", days.stream().filter(Day::whoopRecovery).count());
        fields.put("whoopStrain", days.stream().filter(Day::whoopStrain).count());
        fields.put("whoopSleep", days.stream().filter(Day::whoopSleep).count());

        return new Report(
                from, to, days.size(),
                days.stream().filter(Day::garminComplete).count(),
                days.stream().filter(Day::whoopComplete).count(),
                days.stream().filter(Day::paired).count(),
                fields, gaps(days), days);
    }

    /** Contiguous runs of days that are not paired. */
    static List<Gap> gaps(List<Day> days) {
        var out = new ArrayList<Gap>();
        LocalDate start = null;
        LocalDate prev = null;
        for (Day d : days) {
            if (!d.paired()) {
                if (start == null) start = d.date();
                prev = d.date();
            } else if (start != null) {
                out.add(new Gap(start, prev, ChronoUnit.DAYS.between(start, prev) + 1));
                start = null;
            }
        }
        if (start != null) out.add(new Gap(start, prev, ChronoUnit.DAYS.between(start, prev) + 1));
        return out;
    }
}
