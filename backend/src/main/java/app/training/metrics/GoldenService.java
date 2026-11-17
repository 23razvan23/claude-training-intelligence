package app.training.metrics;

import app.training.athletes.Athlete;
import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The local golden-file check (R-14, ADR 0017). {@link #freeze} stores per-day hashes of
 * {@code day_features} and of the ACTIVE model's {@code derived_day_metrics} for a window.
 * {@link #check} replays the calculation from the raw data as it was at freeze time (rows from
 * later import batches are removed inside the transaction) and compares. The replay always
 * runs in a transaction that is rolled back, so nothing stored changes.
 *
 * <p>A difference after the replay is a code change. What the replay cannot undo (the profile,
 * the ACTIVE model, raw rows changed in place) is reported separately as "inputs changed".
 */
@Service
public class GoldenService {
    static final int SCHEMA = 1;
    static final String FILE_PREFIX = "golden-athlete-";
    static final int DEFAULT_DAYS = 60;
    /** The morning sync re-fetches this many previous days, so they are still being corrected [configurable]. */
    static final int DEFAULT_MARGIN_DAYS = 2;
    static final int MAX_LISTED_DAYS = 12;
    static final int MAX_LISTED_COLUMNS = 12;

    private static final Set<String> FEATURE_SKIP = Set.of("athlete_id", "cycle_date", "feature_version", "computed_at");
    private static final Set<String> SCORE_SKIP = Set.of("athlete_id", "cycle_date", "model_version", "computed_at");
    /** Depends on when the row was written, not on the code. */
    private static final Set<String> DROP = Set.of("data_quality.stale");
    private static final List<String> RAW_TABLES =
            List.of("daily_wellness", "sleep_session", "activity", "whoop_reference", "monitoring_hr", "activity_stream");
    private static final List<String> CUT_TABLES = List.of("daily_wellness", "sleep_session", "activity", "whoop_reference", "monitoring_hr");

    /** Why a command could not run; {@code exit} is the process exit code (2 = cannot check). */
    public static class Refused extends RuntimeException {
        private final int exit;

        public Refused(String message) {
            this(2, message);
        }

        public Refused(int exit, String message) {
            super(message);
            this.exit = exit;
        }

        public int exit() {
            return exit;
        }
    }

    public record FreezeResult(Path file, int days, LocalDate from, LocalDate to, String modelVersion, String featureVersion) {}

    /** {@code exit}: 0 no difference, 1 code changed, 3 inputs changed. */
    public record CheckResult(int exit, List<String> lines) {}

    private record Snap(Map<String, String> features, Map<String, String> scores) {}

    private record Env(GoldenFile.Profile profile, Map<String, GoldenFile.Raw> raw) {}

    private record Replay(Env env, Map<LocalDate, Snap> days) {}

    private final AthleteRepository athletes;
    private final MetricsRepository models;
    private final FeatureRecomputeService recompute;
    private final ScoringService scoring;
    private final ActivityLoadService loads;
    private final MetricsProperties props;
    private final GoldenProperties golden;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ObjectMapper json;
    private final ObjectMapper exact;
    private final ObjectProvider<BuildProperties> build;

    public GoldenService(AthleteRepository athletes, MetricsRepository models, FeatureRecomputeService recompute, ScoringService scoring,
                         ActivityLoadService loads, MetricsProperties props, GoldenProperties golden, JdbcClient jdbc,
                         TransactionTemplate tx, Clock clock, ObjectMapper json, ObjectProvider<BuildProperties> build) {
        this.athletes = athletes;
        this.models = models;
        this.recompute = recompute;
        this.scoring = scoring;
        this.loads = loads;
        this.props = props;
        this.golden = golden;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.json = json;
        // jsonb numbers are read exactly, not as doubles
        this.exact = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.build = build;
    }

    // ---------------------------------------------------------------- freeze

    /**
     * @param from first day; null: {@value #DEFAULT_DAYS} days ending at {@code to}
     * @param to last day; null: {@value #DEFAULT_MARGIN_DAYS} days before the last day with features
     * @param scoresOnly freeze and later replay only the scoring, from the stored features
     */
    public FreezeResult freeze(long athleteId, LocalDate from, LocalDate to, boolean scoresOnly) {
        Athlete athlete = athlete(athleteId);
        refuseWhileImporting(athleteId);
        ModelVersion model = activeModel(athleteId);
        LocalDate last = jdbc.sql("SELECT max(cycle_date) FROM day_features WHERE athlete_id = ? AND feature_version = ?")
                .params(athleteId, model.featureVersion()).query(LocalDate.class).optional().orElse(null);
        if (last == null) throw new Refused("There are no stored features: press Recompute features first.");
        LocalDate end = to != null ? to : last.minusDays(DEFAULT_MARGIN_DAYS);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        if (start.isAfter(end)) throw new Refused(64, "--from is after --to.");

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        long watermark = maxBatchId(athleteId);
        Map<LocalDate, Snap> stored = snapshot(athleteId, model, start, end, salt);
        Replay replay = replay(athlete, model, null, scoresOnly, start, end, salt);

        // The stored rows must be what the current code produces now, or a later check could never come out clean.
        var stale = differences(stored, replay.days(), true, true);
        if (!stale.isEmpty()) {
            var lines = new ArrayList<String>();
            lines.add("Not frozen: the stored rows differ from a fresh calculation by the current code, so a check would start dirty.");
            lines.add("Press Recompute features (Calibration page), then freeze again. If it still differs, the code changed since the last Recompute.");
            lines.addAll(listDifferences(stale));
            throw new Refused(String.join(System.lineSeparator(), lines));
        }
        if (maxBatchId(athleteId) != watermark) throw new Refused("An import or sync started while freezing. Wait for it to finish and run freeze again.");
        if (stored.isEmpty()) throw new Refused("There are no stored days between " + start + " and " + end + ".");

        var days = new TreeMap<String, GoldenFile.Day>();
        stored.forEach((d, s) -> days.put(d.toString(), new GoldenFile.Day(
                GoldenHasher.rowHash(s.features()), GoldenHasher.rowHash(s.scores()), s.features(), s.scores())));
        var b = build.getIfAvailable();
        var file = new GoldenFile(SCHEMA, scoresOnly ? "scores" : "full", clock.instant(), athleteId, start, end, model.featureVersion(),
                model.version(), GoldenHasher.fingerprint(json, model.coefficients()), b == null ? "unknown" : b.getVersion(), commit(),
                Base64.getEncoder().encodeToString(salt), replay.env().profile(), watermark, replay.env().raw(), days);
        Path path = write(file);
        return new FreezeResult(path, days.size(), start, end, model.version(), model.featureVersion());
    }

    // ----------------------------------------------------------------- check

    /** @param fileOrNull the golden file to use; null: the athlete's newest */
    public CheckResult check(long athleteId, Path fileOrNull) {
        Athlete athlete = athlete(athleteId);
        Path path = fileOrNull != null ? fileOrNull : newest(athleteId);
        GoldenFile file = read(path);
        if (file.athleteId() != athleteId) throw new Refused("That golden file belongs to athlete " + file.athleteId() + ".");
        refuseWhileImporting(athleteId);
        ModelVersion model = activeModel(athleteId);
        if (!model.featureVersion().equals(file.featureVersion())) {
            throw new Refused("Features were frozen as " + file.featureVersion() + " but the ACTIVE model uses " + model.featureVersion()
                    + ". Freeze again (the feature version changing is a deliberate change).");
        }
        boolean scoresOnly = "scores".equals(file.mode());
        byte[] salt = Base64.getDecoder().decode(file.salt());
        Replay r = replay(athlete, model, file.batchWatermark(), scoresOnly, file.from(), file.to(), salt);

        // What the replay can't undo.
        var inputs = new ArrayList<String>();
        var p = file.profile();
        var now = r.env().profile();
        var profile = new ArrayList<String>();
        if (!java.util.Objects.equals(p.hrMax(), now.hrMax())) profile.add("hr_max");
        if (!java.util.Objects.equals(p.restingHr(), now.restingHr())) profile.add("resting_hr");
        if (!java.util.Objects.equals(p.sex(), now.sex())) profile.add("sex");
        if (!java.util.Objects.equals(p.timezone(), now.timezone())) profile.add("timezone");
        if (!profile.isEmpty()) inputs.add("the athlete's profile changed (" + String.join(", ", profile) + ")");
        boolean scoresComparable = true;
        if (!model.version().equals(file.modelVersion())) {
            inputs.add("the ACTIVE model changed (" + file.modelVersion() + " to " + model.version() + "): scores were not compared");
            scoresComparable = false;
        } else if (!GoldenHasher.fingerprint(json, model.coefficients()).equals(file.modelFingerprint())) {
            inputs.add("the coefficients of model " + model.version() + " changed: scores were not compared");
            scoresComparable = false;
        }
        if (!scoresOnly) {
            for (String t : RAW_TABLES) {
                var was = file.raw().get(t);
                var is = r.env().raw().get(t);
                if (was == null || !was.equals(is)) inputs.add("raw data in " + t + " changed since the freeze (rows removed, or edited in place)");
            }
        }

        var diffs = differences(frozenDays(file), r.days(), true, scoresComparable);
        var out = new ArrayList<String>();
        out.add("Golden check, athlete " + athleteId + ": " + path.getFileName() + " (frozen " + file.frozenAt() + ", app " + file.appVersion()
                + ", commit " + file.commit() + ")");
        out.add("Window " + file.from() + " to " + file.to() + ", " + file.days().size() + " days, features " + file.featureVersion()
                + ", model " + file.modelVersion() + ", mode " + file.mode() + ".");
        int exit;
        if (!inputs.isEmpty()) {
            exit = 3;
            out.add("INPUTS CHANGED since the freeze (the check cannot replay these):");
            inputs.forEach(s -> out.add("  - " + s));
            if (diffs.isEmpty()) {
                out.add("No day differs in what could be compared, but the check is incomplete. If the change was expected, freeze again.");
            } else {
                out.add(diffs.size() + " days differ; they may come from the changed inputs, so this is not proof of a code change:");
                out.addAll(listDifferences(diffs));
                out.add("If the change was expected, freeze again.");
            }
        } else if (!diffs.isEmpty()) {
            exit = 1;
            out.add("CODE CHANGED: " + count(diffs) + " differences in " + diffs.size() + " days (the raw data, profile and model are as frozen).");
            out.addAll(listDifferences(diffs));
        } else {
            exit = 0;
            out.add("0 differences");
        }
        return new CheckResult(exit, out);
    }

    // ---------------------------------------------------------------- replay

    /**
     * Recomputes in a transaction that is always rolled back. In "full" mode: first removes the raw rows of import batches after
     * {@code cutBatch}, then does what Recompute features does (loads, features, scores). In "scores" mode: only the scoring.
     */
    private Replay replay(Athlete athlete, ModelVersion model, Long cutBatch, boolean scoresOnly, LocalDate from, LocalDate to, byte[] salt) {
        try {
            return tx.execute(status -> {
                try {
                    if (!scoresOnly && cutBatch != null) {
                        for (String t : CUT_TABLES) {
                            jdbc.sql("DELETE FROM " + t + " WHERE athlete_id = ? AND import_batch_id > ?").params(athlete.id(), cutBatch).update();
                        }
                    }
                    Env env = environment(athlete, scoresOnly);
                    if (scoresOnly) scoring.recompute(athlete.id(), null, null);
                    else recompute.recompute(athlete.id(), null, FeatureRecomputeService.SOURCE_MANUAL);
                    return new Replay(env, snapshot(athlete.id(), model, from, to, salt));
                } finally {
                    status.setRollbackOnly();
                }
            });
        } catch (ApiException e) {
            throw new Refused("Cannot score: " + e.getMessage());
        }
    }

    private Env environment(Athlete athlete, boolean scoresOnly) {
        var profile = new GoldenFile.Profile(athlete.hrMax(), loads.restingHr(athlete), athlete.sex(), athlete.timezone());
        var raw = new TreeMap<String, GoldenFile.Raw>();
        if (!scoresOnly) {
            // row text carries timestamps, so make the session zone fixed while hashing
            jdbc.sql("SELECT set_config('TimeZone', 'UTC', true)").query().singleRow();
            for (String t : RAW_TABLES) {
                var row = jdbc.sql(checksumSql(t)).param(athlete.id()).query().singleRow();
                raw.put(t, new GoldenFile.Raw(((Number) row.get("n")).longValue(), (String) row.get("c")));
            }
            jdbc.sql("RESET TIME ZONE").update();
        }
        return new Env(profile, raw);
    }

    private static String checksumSql(String table) {
        String select = "SELECT count(*) AS n, coalesce(sum(hashtextextended(%s, 0)::numeric), 0)::text AS c FROM %s WHERE %s";
        return switch (table) {
            case "monitoring_hr" -> select.formatted("concat_ws('|', extract(epoch FROM ts)::text, hr::text)", "monitoring_hr", "athlete_id = ?");
            case "activity_stream" -> select.formatted("concat_ws('|', s.activity_id::text, extract(epoch FROM s.ts)::text, s.hr::text)",
                    "activity_stream s JOIN activity a ON a.id = s.activity_id", "a.athlete_id = ?");
            default -> select.formatted("t::text", table + " t", "athlete_id = ?");
        };
    }

    // -------------------------------------------------------------- snapshot

    private Map<LocalDate, Snap> snapshot(long athleteId, ModelVersion model, LocalDate from, LocalDate to, byte[] salt) {
        var features = rows("SELECT * FROM day_features WHERE athlete_id = ? AND feature_version = ? AND cycle_date BETWEEN ? AND ?",
                FEATURE_SKIP, salt, athleteId, model.featureVersion(), from, to);
        var scores = rows("SELECT * FROM derived_day_metrics WHERE athlete_id = ? AND model_version = ? AND cycle_date BETWEEN ? AND ?",
                SCORE_SKIP, salt, athleteId, model.version(), from, to);
        var days = new TreeMap<LocalDate, Snap>();
        for (var d : new TreeSet<>(Stream.concat(features.keySet().stream(), scores.keySet().stream()).toList())) {
            days.put(d, new Snap(features.getOrDefault(d, Map.of()), scores.getOrDefault(d, Map.of())));
        }
        return days;
    }

    private Map<LocalDate, Map<String, String>> rows(String sql, Set<String> skip, byte[] salt, Object... params) {
        var out = new HashMap<LocalDate, Map<String, String>>();
        jdbc.sql(sql).params(params).query((ResultSet rs, int i) -> {
            try {
                var plain = new LinkedHashMap<String, String>();
                var jsonColumns = new LinkedHashMap<String, String>();
                var md = rs.getMetaData();
                for (int c = 1; c <= md.getColumnCount(); c++) {
                    String name = md.getColumnName(c);
                    if (skip.contains(name)) continue;
                    if ("jsonb".equals(md.getColumnTypeName(c))) jsonColumns.put(name, rs.getString(c));
                    else plain.put(name, rs.getString(c));
                }
                out.put(rs.getObject("cycle_date", LocalDate.class), GoldenHasher.hashes(salt, GoldenHasher.leaves(exact, plain, jsonColumns, DROP)));
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }).list();
        return out;
    }

    // ------------------------------------------------------------ comparison

    private Map<LocalDate, Snap> frozenDays(GoldenFile f) {
        var m = new TreeMap<LocalDate, Snap>();
        f.days().forEach((d, day) -> m.put(LocalDate.parse(d), new Snap(
                day.featureColumns() == null ? Map.of() : day.featureColumns(), day.scoreColumns() == null ? Map.of() : day.scoreColumns())));
        return m;
    }

    /** Per day, the names of differing columns: "day_features: …" and "scores: …". */
    private static Map<LocalDate, Map<String, List<String>>> differences(Map<LocalDate, Snap> frozen, Map<LocalDate, Snap> now,
                                                                         boolean features, boolean scores) {
        var out = new TreeMap<LocalDate, Map<String, List<String>>>();
        var days = new TreeSet<LocalDate>(frozen.keySet());
        days.addAll(now.keySet());
        for (LocalDate d : days) {
            Snap a = frozen.getOrDefault(d, new Snap(Map.of(), Map.of()));
            Snap b = now.getOrDefault(d, new Snap(Map.of(), Map.of()));
            var per = new LinkedHashMap<String, List<String>>();
            if (features) {
                var f = GoldenHasher.differing(a.features(), b.features());
                if (!f.isEmpty()) per.put("day_features", f);
            }
            if (scores) {
                var s = GoldenHasher.differing(a.scores(), b.scores());
                if (!s.isEmpty()) per.put("derived_day_metrics", s);
            }
            if (!per.isEmpty()) out.put(d, per);
        }
        return out;
    }

    private static int count(Map<LocalDate, Map<String, List<String>>> diffs) {
        return diffs.values().stream().flatMap(m -> m.values().stream()).mapToInt(List::size).sum();
    }

    /** Dates and column names only, never values; long lists are cut. */
    static List<String> listDifferences(Map<LocalDate, Map<String, List<String>>> diffs) {
        var lines = new ArrayList<String>();
        int shown = 0;
        for (var e : diffs.entrySet()) {
            if (shown++ == MAX_LISTED_DAYS) {
                lines.add("  ... and " + (diffs.size() - MAX_LISTED_DAYS) + " more days");
                break;
            }
            var parts = new ArrayList<String>();
            e.getValue().forEach((table, cols) -> parts.add(table + ": " + cut(cols)));
            lines.add("  " + e.getKey() + "  " + String.join("; ", parts));
        }
        if (diffs.size() > MAX_LISTED_DAYS) {
            var all = new TreeSet<String>();
            diffs.values().forEach(m -> m.forEach((t, cols) -> cols.forEach(c -> all.add(t + "." + c))));
            lines.add("  columns involved in all differing days: " + cut(List.copyOf(all)));
        }
        return lines;
    }

    private static String cut(List<String> names) {
        if (names.size() <= MAX_LISTED_COLUMNS) return String.join(", ", names);
        return String.join(", ", names.subList(0, MAX_LISTED_COLUMNS)) + " and " + (names.size() - MAX_LISTED_COLUMNS) + " more";
    }

    // ----------------------------------------------------------------- misc

    private Athlete athlete(long id) {
        return athletes.findById(id).orElseThrow(() -> new Refused("Unknown athlete " + id + "."));
    }

    private ModelVersion activeModel(long athleteId) {
        ModelVersion m = models.active(athleteId).orElseThrow(() -> new Refused("There is no ACTIVE model: nothing to freeze or check."));
        if (!props.featureVersion().equals(m.featureVersion())) {
            throw new Refused("The ACTIVE model " + m.version() + " uses feature set " + m.featureVersion() + " but the app computes "
                    + props.featureVersion() + ".");
        }
        return m;
    }

    private void refuseWhileImporting(long athleteId) {
        boolean running = jdbc.sql("SELECT EXISTS (SELECT 1 FROM import_batch WHERE athlete_id = ? AND status IN ('PENDING', 'RUNNING'))")
                .param(athleteId).query(Boolean.class).single();
        if (running) throw new Refused("An import or Garmin sync is running. Wait until it has finished, then run this again.");
    }

    private long maxBatchId(long athleteId) {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM import_batch WHERE athlete_id = ?").param(athleteId).query(Long.class).single();
    }

    private static String commit() {
        String c = System.getenv("GIT_COMMIT");
        return c == null || c.isBlank() ? "unknown" : c.trim();
    }

    private Path write(GoldenFile file) {
        try {
            Files.createDirectories(golden.dir());
            String name = FILE_PREFIX + file.athleteId() + "-"
                    + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(file.frozenAt().getEpochSecond()))
                    + ".json";
            Path target = golden.dir().resolve(name);
            if (Files.exists(target)) throw new Refused("A golden file with that name exists; wait a second and run freeze again.");
            Path tmp = Files.createTempFile(golden.dir(), ".golden-", ".tmp");
            try {
                json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), file);
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // a mounted folder on another file system: keep the defaults
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return target;
        } catch (IOException e) {
            throw new Refused("Cannot write to " + golden.dir() + ": " + e.getMessage()
                    + ". Is ./backups/golden mounted and writable? (mkdir -p backups/golden)");
        }
    }

    private Path newest(long athleteId) {
        String prefix = FILE_PREFIX + athleteId + "-";
        if (!Files.isDirectory(golden.dir())) throw new Refused("No golden file yet (" + golden.dir() + " does not exist). Run: golden freeze --athlete " + athleteId);
        try (Stream<Path> s = Files.list(golden.dir())) {
            Optional<Path> p = s.filter(f -> f.getFileName().toString().startsWith(prefix) && f.getFileName().toString().endsWith(".json"))
                    .max(java.util.Comparator.comparing(f -> f.getFileName().toString()));
            return p.orElseThrow(() -> new Refused("No golden file for athlete " + athleteId + " yet. Run: golden freeze --athlete " + athleteId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private GoldenFile read(Path path) {
        try {
            var f = json.readValue(path.toFile(), GoldenFile.class);
            if (f.schema() != SCHEMA) throw new Refused("Golden file " + path.getFileName() + " has format " + f.schema() + "; freeze again.");
            return f;
        } catch (IOException e) {
            throw new Refused("Cannot read golden file " + path + ": " + e.getMessage());
        }
    }
}
