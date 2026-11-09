package app.training.ingestion;

import app.training.athletes.AthleteRepository;
import app.training.ingestion.garmin.GarminExportImporter;
import app.training.ingestion.whoop.WhoopCsvImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one import batch in the background, inside a single transaction: a batch
 * either lands completely or not at all. Uploaded files are deleted afterwards
 * so no copy of the athlete's export lingers on disk.
 */
@Component
public class ImportRunner {
    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final ImportBatchRepository batches;
    private final AthleteRepository athletes;
    private final RawWriter writer;
    private final WhoopCsvImporter whoop;
    private final GarminExportImporter garmin;
    private final TransactionTemplate tx;

    public ImportRunner(ImportBatchRepository batches, AthleteRepository athletes, RawWriter writer,
                        WhoopCsvImporter whoop, GarminExportImporter garmin, TransactionTemplate tx) {
        this.batches = batches;
        this.athletes = athletes;
        this.writer = writer;
        this.whoop = whoop;
        this.garmin = garmin;
        this.tx = tx;
    }

    @Async
    public void runAsync(long batchId, long athleteId, ImportKind kind, List<Path> files, String sha256) {
        run(batchId, athleteId, kind, files, sha256);
    }

    public void run(long batchId, long athleteId, ImportKind kind, List<Path> files, String sha256) {
        var stats = new ImportStats();
        try {
            batches.markRunning(batchId);
            if (sha256 != null) {
                batches.previousSuccessWithHash(athleteId, sha256, batchId).ifPresent(prev ->
                        stats.warn("This exact file was already imported in batch " + prev + "; duplicates were skipped."));
            }
            var ctx = new RawWriter.Ctx(athleteId, batchId, stats);
            var zone = athletes.zoneOf(athleteId);
            tx.executeWithoutResult(status -> {
                try {
                    switch (kind) {
                        case WHOOP_EXPORT -> whoop.importFiles(ctx, files, zone);
                        case GARMIN_EXPORT -> garmin.importExport(ctx, files.getFirst(), zone);
                        case FIT_UPLOAD -> {
                            for (Path p : files) garmin.fit(ctx, p.getFileName().toString(), Files.readAllBytes(p), zone);
                        }
                        case GARMIN_SYNC -> throw new UnsupportedOperationException("Garmin sync arrives in Phase 1");
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            batches.markFinished(batchId, stats);
            log.info("Import batch {} ({}) finished: {} rows inserted", batchId, kind, stats.insertedTotal());
        } catch (RuntimeException e) {
            // Log the type and message only: never the payload, which is health data.
            log.warn("Import batch {} ({}) failed: {}", batchId, kind, e.toString());
            batches.markFailed(batchId, safeMessage(e), stats);
        } finally {
            for (Path p : files) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete upload {}", p.getFileName());
                }
            }
        }
    }

    private static String safeMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return root.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 500 ? m.substring(0, 500) : m));
    }
}
