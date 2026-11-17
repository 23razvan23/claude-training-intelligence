package app.training.garmin;

import app.training.common.ApiException;
import app.training.ingestion.ImportRunner;
import app.training.ingestion.ImportStats;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "Check against export" (ADR 0015 D5): fetches days that are already in the database from a
 * Garmin export, imports them in a transaction that is always rolled back, and reports what the
 * sync would have added and which stored values it would have changed. Nothing is kept, so the
 * scores and the live holdout are untouched. It reports column names and counts, never values.
 *
 * <p>Days fully covered by an export should give no new rows and no differences in a column the
 * scores use; anything else shows where the sync's translation (GarminApiShapes) needs fixing.
 */
@Service
public class GarminSyncCheckService {

    /** Columns the day features read (DayFeatureService); a difference there would move a score. */
    static final Map<String, Set<String>> USED_BY_SCORES = Map.of(
            "daily_wellness", Set.of("resting_hr", "hrv_rmssd_ms", "body_battery_high"),
            "sleep_session", Set.of("start_at", "end_at", "cycle_date", "deep_s", "light_s", "rem_s", "sleep_score",
                    "avg_respiration", "avg_spo2_pct"));

    static final Map<String, List<String>> COMPARED = Map.of(
            "daily_wellness", List.of("resting_hr", "hrv_rmssd_ms", "body_battery_high", "body_battery_low", "hrv_status",
                    "respiration_rate", "spo2_pct", "stress_avg"),
            "sleep_session", List.of("start_at", "end_at", "cycle_date", "deep_s", "light_s", "rem_s", "awake_s", "sleep_score",
                    "avg_respiration", "avg_spo2_pct", "lowest_spo2_pct", "avg_sleep_stress"));

    /**
     * One stored value the sync would change. {@code outcome}: DIFFERENT, MISSING_IN_SYNC (stored,
     * but Garmin's live data has none), ONLY_IN_SYNC (new value where nothing was stored) or
     * NEW_RECORD (no stored version of this record at all; {@code column} is null).
     */
    public record Difference(String table, LocalDate day, String record, String column, boolean usedByScores, String outcome) {}

    /**
     * @param sleepFieldSearch where in Garmin's live sleep answer the stored export values are (D11):
     *     field paths and match counts, never values
     */
    public record Report(LocalDate from, LocalDate to, Map<String, ImportStats.Counter> tables, List<Difference> differences,
                         int differencesUsedByScores, List<String> missing, List<String> warnings,
                         List<SleepFieldSearch.Result> sleepFieldSearch) {}

    private final GarminSyncService sync;
    private final SidecarClient sidecar;
    private final ImportRunner runner;
    private final GarminSyncProperties props;
    private final JdbcClient jdbc;

    public GarminSyncCheckService(GarminSyncService sync, SidecarClient sidecar, ImportRunner runner, GarminSyncProperties props,
                                  JdbcClient jdbc) {
        this.sync = sync;
        this.sidecar = sidecar;
        this.runner = runner;
        this.props = props;
        this.jdbc = jdbc;
    }

    public Report check(long athleteId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("Choose a first and a last day");
        if (to.isAfter(sync.today(athleteId))) throw ApiException.badRequest("The last day cannot be in the future");
        if (GarminSyncService.days(from, to).size() > props.checkMaxDays()) {
            throw ApiException.badRequest("Check at most " + props.checkMaxDays() + " days at a time");
        }
        sync.requireConnected(athleteId);
        String tokens = sync.tokens(athleteId);
        JsonNode payload;
        try {
            payload = sidecar.fetchDays(tokens, GarminSyncService.days(from, to), props.timeoutSeconds());
        } catch (SidecarClient.SidecarException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Garmin could not be read: " + e.code(), e.code());
        }
        sync.storeRefreshed(athleteId, tokens, payload.get("tokens").asText());
        var missing = new ArrayList<String>();
        payload.path("missing").forEach(m -> {
            if (m.asText("").matches("[A-Za-z]+:[0-9A-Za-z-]{1,40}")) missing.add(m.asText());
        });
        return runner.dryRunSync(athleteId, payload, (batchId, stats) -> {
            var diffs = new ArrayList<Difference>();
            diffs.addAll(differences("daily_wellness", "calendar_date", batchId));
            diffs.addAll(differences("sleep_session", "cycle_date", batchId));
            int used = (int) diffs.stream().filter(Difference::usedByScores).count();
            return new Report(from, to, stats.counters(), diffs, used, missing, stats.warnings(),
                    sleepFieldSearch(athleteId, batchId, payload));
        });
    }

    /** Rows this (uncommitted) batch added, each compared with the newest earlier version of the same record. */
    private List<Difference> differences(String table, String dayColumn, long batchId) {
        List<String> cols = COMPARED.get(table);
        var select = new StringBuilder("SELECT n.").append(dayColumn).append(" AS day, split_part(n.source_id, '@', 1) AS record, p.id AS prev_id");
        for (String c : cols) select.append(", n.").append(c).append(" AS n_").append(c).append(", p.").append(c).append(" AS p_").append(c);
        String dayMatch = "daily_wellness".equals(table) ? " AND p.calendar_date = n.calendar_date" : "";
        String sql = select + " FROM " + table + " n LEFT JOIN LATERAL (SELECT * FROM " + table + " p"
                + " WHERE p.athlete_id = n.athlete_id AND p.source = n.source" + dayMatch
                + " AND split_part(p.source_id, '@', 1) = split_part(n.source_id, '@', 1)"
                + " AND p.import_batch_id IS DISTINCT FROM n.import_batch_id"
                + " ORDER BY p.ingested_at DESC, p.id DESC LIMIT 1) p ON true"
                + " WHERE n.import_batch_id = ? ORDER BY 1, 2";
        var out = new ArrayList<Difference>();
        jdbc.sql(sql).param(batchId).query(rs -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            String record = rs.getString("record");
            if (rs.getObject("prev_id") == null) {
                out.add(new Difference(table, day, record, null, false, "NEW_RECORD"));
                return;
            }
            for (String c : cols) {
                Object n = rs.getObject("n_" + c);
                Object p = rs.getObject("p_" + c);
                if (same(n, p)) continue;
                String outcome = n == null ? "MISSING_IN_SYNC" : p == null ? "ONLY_IN_SYNC" : "DIFFERENT";
                out.add(new Difference(table, day, record, c, USED_BY_SCORES.get(table).contains(c), outcome));
            }
        });
        return out;
    }

    /** The stored export values of the fetched nights (not this batch's), and the search over the live answers. */
    private List<SleepFieldSearch.Result> sleepFieldSearch(long athleteId, long batchId, JsonNode payload) {
        var answers = new java.util.LinkedHashMap<String, JsonNode>();
        for (JsonNode day : payload.path("days")) {
            JsonNode answer = day.get("sleep");
            if (answer == null || !answer.isObject()) continue;
            String night = answer.path("dailySleepDTO").path("calendarDate").asText(day.path("date").asText(""));
            if (!night.isEmpty()) answers.put(night, answer);
        }
        var stored = new java.util.HashMap<String, Map<String, BigDecimal>>();
        if (answers.isEmpty()) return SleepFieldSearch.search(answers, stored);
        jdbc.sql("""
                SELECT DISTINCT ON (split_part(source_id, '@', 1)) split_part(source_id, '@', 1) AS night,
                       avg_respiration, avg_spo2_pct, lowest_spo2_pct, avg_sleep_stress
                FROM sleep_session
                WHERE athlete_id = :a AND source = 'GARMIN' AND NOT is_nap AND import_batch_id IS DISTINCT FROM :b
                  AND split_part(source_id, '@', 1) IN (:nights)
                ORDER BY split_part(source_id, '@', 1), ingested_at DESC, id DESC""")
                .param("a", athleteId).param("b", batchId).param("nights", List.copyOf(answers.keySet()))
                .query(rs -> {
                    var values = new java.util.HashMap<String, BigDecimal>();
                    for (String c : SleepFieldSearch.COLUMNS.keySet()) values.put(c, rs.getBigDecimal(c));
                    stored.put(rs.getString("night"), values);
                });
        return SleepFieldSearch.search(answers, stored);
    }

    static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }
}
