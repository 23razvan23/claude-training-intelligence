package app.training.ingestion.whoop;

import static app.training.ingestion.whoop.WhoopColumns.*;

import app.training.ingestion.CsvTable;
import app.training.ingestion.RawRows;
import app.training.ingestion.RawWriter;
import app.training.ingestion.SportNames;
import app.training.ingestion.TimeParsing;
import app.training.ingestion.whoop.WhoopColumns.FileType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Imports the one-time Whoop data export: physiological_cycles.csv, sleeps.csv,
 * workouts.csv and journal_entries.csv, uploaded loose or as the export ZIP.
 * Files are recognised by their headers. See ADR 0004 for the assumed columns.
 */
@Component
public class WhoopCsvImporter {
    private final RawWriter writer;

    public WhoopCsvImporter(RawWriter writer) {
        this.writer = writer;
    }

    private record Named(String name, CsvTable table, FileType type) {}

    public void importFiles(RawWriter.Ctx ctx, List<Path> files, ZoneId athleteZone) throws IOException {
        var tables = new ArrayList<Named>();
        for (Path p : files) {
            if (isZip(p)) {
                try (var zin = new ZipInputStream(Files.newInputStream(p))) {
                    ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                            tables.add(load(e.getName(), zin));
                        }
                    }
                }
            } else {
                try (InputStream in = Files.newInputStream(p)) {
                    tables.add(load(p.getFileName().toString(), in));
                }
            }
        }
        // Cycles first, then sleeps, workouts, journal: order matters only for readable stats.
        tables.sort(Comparator.comparing(Named::type));
        for (var n : tables) {
            ctx.progress().phase("whoop", (long) n.table().records().size());
            switch (n.type()) {
                case CYCLES -> cycles(ctx, n.table(), athleteZone);
                case SLEEPS -> sleeps(ctx, n.table(), athleteZone);
                case WORKOUTS -> workouts(ctx, n.table(), athleteZone);
                case JOURNAL -> journal(ctx, n.table(), athleteZone);
                case UNKNOWN -> ctx.stats().warn("Skipped " + n.name() + ": not a recognised Whoop CSV (headers: "
                        + String.join(", ", n.table().headers()) + ")");
            }
        }
        if (tables.stream().noneMatch(t -> t.type() == FileType.CYCLES)) {
            ctx.stats().warn("No physiological_cycles.csv found: recovery and strain references were not imported.");
        }
    }

    private static boolean isZip(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        }
    }

    private static Named load(String name, InputStream in) throws IOException {
        // Do not close the reader: for ZIP entries it would close the whole archive stream.
        var bytes = in.readAllBytes();
        var table = CsvTable.read(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        return new Named(name, table, FileType.detect(table));
    }

    private static ZoneId zone(CsvTable t, CSVRecord r, ZoneId fallback) {
        return TimeParsing.zone(t.text(r, CYCLE_TIMEZONE), fallback);
    }

    private static Instant cycleStart(CsvTable t, CSVRecord r, ZoneId z) {
        return TimeParsing.instant(t.text(r, CYCLE_START), z);
    }

    /**
     * The day a Whoop cycle belongs to: the local date you woke up (ADR 0002). Whoop's export
     * starts a cycle at sleep onset, usually the evening before, so the start date alone is one
     * day early. Uses the wake onset when the row has one; otherwise start + 12 h, which lands
     * on the wake date whether the cycle starts at sleep onset or at waking.
     */
    static LocalDate cycleDate(Instant cycleStart, Instant wakeOnset, ZoneId z) {
        if (wakeOnset != null && !wakeOnset.isBefore(cycleStart) && Duration.between(cycleStart, wakeOnset).toHours() < 20) {
            return LocalDate.ofInstant(wakeOnset, z);
        }
        return LocalDate.ofInstant(cycleStart.plus(Duration.ofHours(12)), z);
    }

    private static Integer minutesToSeconds(Integer min) {
        return min == null ? null : min * 60;
    }

    /**
     * An export taken during the day contains the current cycle with a blank "Cycle end time" and a
     * day strain that is still growing. It is not a measurement yet, and the raw tables never update
     * a stored row, so storing it would freeze the partial value (R-06, ADR 0003).
     */
    private static final String UNFINISHED_DAY_WARNING =
            "The current, unfinished day was not imported; it will come in with your next export.";

    private void cycles(RawWriter.Ctx ctx, CsvTable t, ZoneId fallback) {
        boolean skippedOpen = false;
        for (CSVRecord r : t.records()) {
            ctx.progress().step();
            try {
                ZoneId z = zone(t, r, fallback);
                Instant start = cycleStart(t, r, z);
                if (start == null) {
                    ctx.stats().skipped("whoop_reference", 1);
                    continue;
                }
                Instant end = TimeParsing.instant(t.text(r, CYCLE_END), z);
                if (end == null) {
                    ctx.stats().skipped("whoop_reference", 1);
                    skippedOpen = true;
                    continue;
                }
                writer.whoopCycle(ctx, new RawRows.WhoopCycle(
                        start,
                        end,
                        cycleDate(start, TimeParsing.instant(t.text(r, WAKE_ONSET), z), z),
                        t.integer(r, RECOVERY),
                        t.decimal(r, DAY_STRAIN),
                        t.decimal(r, HRV),
                        t.integer(r, RESTING_HR),
                        t.decimal(r, SKIN_TEMP),
                        t.decimal(r, SPO2),
                        t.decimal(r, RESPIRATORY_RATE),
                        t.integer(r, SLEEP_PERFORMANCE),
                        t.integer(r, SLEEP_NEED_MIN),
                        t.integer(r, SLEEP_DEBT_MIN),
                        t.integer(r, ASLEEP_MIN),
                        t.integer(r, ENERGY)));
            } catch (RuntimeException e) {
                ctx.stats().skipped("whoop_reference", 1);
                ctx.stats().warn("physiological_cycles.csv line " + r.getRecordNumber() + ": " + e.getMessage());
            }
        }
        if (skippedOpen) ctx.stats().warn(UNFINISHED_DAY_WARNING);
    }

    private void sleeps(RawWriter.Ctx ctx, CsvTable t, ZoneId fallback) {
        for (CSVRecord r : t.records()) {
            ctx.progress().step();
            try {
                ZoneId z = zone(t, r, fallback);
                Instant onset = TimeParsing.instant(t.text(r, SLEEP_ONSET), z);
                Instant wake = TimeParsing.instant(t.text(r, WAKE_ONSET), z);
                if (onset == null || wake == null || !wake.isAfter(onset)) {
                    ctx.stats().skipped("sleep_session", 1);
                    continue;
                }
                writer.sleep(ctx, new RawRows.Sleep(
                        RawRows.WHOOP,
                        onset.toString(),
                        onset,
                        wake,
                        LocalDate.ofInstant(wake, z),
                        Boolean.TRUE.equals(t.bool(r, NAP)),
                        minutesToSeconds(t.integer(r, DEEP_MIN)),
                        minutesToSeconds(t.integer(r, LIGHT_MIN)),
                        minutesToSeconds(t.integer(r, REM_MIN)),
                        minutesToSeconds(t.integer(r, AWAKE_MIN)),
                        t.integer(r, SLEEP_PERFORMANCE),
                        t.decimal(r, RESPIRATORY_RATE),
                        null,
                        null,
                        null));
            } catch (RuntimeException e) {
                ctx.stats().skipped("sleep_session", 1);
                ctx.stats().warn("sleeps.csv line " + r.getRecordNumber() + ": " + e.getMessage());
            }
        }
    }

    private void workouts(RawWriter.Ctx ctx, CsvTable t, ZoneId fallback) {
        for (CSVRecord r : t.records()) {
            ctx.progress().step();
            try {
                ZoneId z = zone(t, r, fallback);
                Instant start = TimeParsing.instant(t.text(r, WORKOUT_START), z);
                if (start == null) {
                    ctx.stats().skipped("activity", 1);
                    continue;
                }
                Instant end = TimeParsing.instant(t.text(r, WORKOUT_END), z);
                Integer durationS = end != null
                        ? (int) Duration.between(start, end).toSeconds()
                        : minutesToSeconds(t.integer(r, WORKOUT_DURATION_MIN));
                String name = t.text(r, ACTIVITY_NAME);
                writer.activity(ctx, new RawRows.Activity(
                        RawRows.WHOOP,
                        start.toString(),
                        SportNames.normalize(name),
                        name,
                        start,
                        LocalDate.ofInstant(start, z),
                        durationS,
                        t.decimal(r, DISTANCE_M),
                        t.decimal(r, ALTITUDE_GAIN_M),
                        t.integer(r, AVG_HR),
                        t.integer(r, MAX_HR),
                        t.integer(r, ENERGY),
                        null,
                        null,
                        null,
                        t.decimal(r, ACTIVITY_STRAIN)));
            } catch (RuntimeException e) {
                ctx.stats().skipped("activity", 1);
                ctx.stats().warn("workouts.csv line " + r.getRecordNumber() + ": " + e.getMessage());
            }
        }
    }

    private void journal(RawWriter.Ctx ctx, CsvTable t, ZoneId fallback) {
        boolean skippedOpen = false;
        for (CSVRecord r : t.records()) {
            ctx.progress().step();
            try {
                ZoneId z = zone(t, r, fallback);
                Instant start = cycleStart(t, r, z);
                String q = t.text(r, QUESTION);
                if (start == null || q == null) {
                    ctx.stats().skipped("whoop_journal_entry", 1);
                    continue;
                }
                if (TimeParsing.instant(t.text(r, CYCLE_END), z) == null) {
                    // Journal rows of an unfinished cycle: same rule as the cycle itself.
                    ctx.stats().skipped("whoop_journal_entry", 1);
                    skippedOpen = true;
                    continue;
                }
                writer.journal(ctx, new RawRows.JournalEntry(
                        start, cycleDate(start, null, z), q, t.bool(r, ANSWERED_YES), t.text(r, NOTES)));
            } catch (RuntimeException e) {
                ctx.stats().skipped("whoop_journal_entry", 1);
                ctx.stats().warn("journal_entries.csv line " + r.getRecordNumber() + ": " + e.getMessage());
            }
        }
        if (skippedOpen) ctx.stats().warn(UNFINISHED_DAY_WARNING);
    }
}