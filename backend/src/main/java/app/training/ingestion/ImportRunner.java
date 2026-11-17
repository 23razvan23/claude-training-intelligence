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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
    private static final long PROGRESS_INTERVAL_MS = 2_000;
    private static final long PROGRESS_LOG_INTERVAL_NANOS = 30_000_000_000L;

    private final TransactionTemplate tx;
    private final TransactionTemplate progressTx;

    public ImportRunner(ImportBatchRepository batches, AthleteRepository athletes, RawWriter writer,
                        WhoopCsvImporter whoop, GarminExportImporter garmin, TransactionTemplate tx,
                        PlatformTransactionManager txManager) {
        this.batches = batches;
        this.athletes = athletes;
        this.writer = writer;
        this.whoop = whoop;
        this.garmin = garmin;
        this.tx = tx;
        this.progressTx = new TransactionTemplate(txManager);
        this.progressTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Progress for a batch, committed in its own transaction so pollers see it while the
     * import's long transaction is still open. Also logs a line on each phase change and
     * every 30 s.
     */
    ImportProgress progressFor(long batchId) {
        var lastLog = new long[] {0};
        var lastPhase = new String[] {null};
        return new ImportProgress(p -> {
            try {
                progressTx.executeWithoutResult(s -> batches.updateProgress(batchId, p));
            } catch (org.springframework.dao.DataAccessException e) {
                // Progress is best effort: never let it stall or fail the import itself.
                log.debug("Import batch {}: progress update skipped ({})", batchId, e.getMostSpecificCause().getMessage());
            }
            long now = System.nanoTime();
            if (!java.util.Objects.equals(p.phase(), lastPhase[0]) || now - lastLog[0] >= PROGRESS_LOG_INTERVAL_NANOS) {
                lastPhase[0] = p.phase();
                lastLog[0] = now;
                log.info("Import batch {}: {} {}{}", batchId, p.phase(), p.done(), p.total() == null ? "" : "/" + p.total());
            }
        }, PROGRESS_INTERVAL_MS);
    }

    @Async
    public void runAsync(long batchId, long athleteId, ImportKind kind, List<Path> files, String sha256) {
        run(batchId, athleteId, kind, files, sha256);
    }

    public void run(long batchId, long athleteId, ImportKind kind, List<Path> files, String sha256) {
        var stats = new ImportStats();
        try {
            batches.markRunning(batchId);
            log.info("Import batch {} ({}) started: {} file(s)", batchId, kind, files.size());
            if (sha256 != null) {
                batches.previousSuccessWithHash(athleteId, sha256, batchId).ifPresent(prev ->
                        stats.warn("This exact file was already imported in batch " + prev + "; duplicates were skipped."));
            }
            var progress = progressFor(batchId);
            var ctx = new RawWriter.Ctx(athleteId, batchId, stats, progress);
            var zone = athletes.zoneOf(athleteId);
            tx.executeWithoutResult(status -> {
                try {
                    switch (kind) {
                        case WHOOP_EXPORT -> whoop.importFiles(ctx, files, zone);
                        case GARMIN_EXPORT -> garmin.importExport(ctx, files.getFirst(), zone);
                        case FIT_UPLOAD -> {
                            progress.phase("fit", (long) files.size());
                            for (Path p : files) {
                                garmin.fit(ctx, p.getFileName().toString(), Files.readAllBytes(p), zone);
                                progress.step();
                            }
                        }
                        case GARMIN_SYNC -> throw new IllegalArgumentException("A Garmin sync is imported with runSync");
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            progress.flush();
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

    /** A sync import that failed; the batch is already marked FAILED with the same fixed message. */
    public static final class SyncImportFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyncImportFailed(String message) {
            super(message);
        }
    }

    /**
     * Imports one Garmin sync fetch as batch {@code batchId}, in one transaction like any import
     * (ADR 0006), through the export's own record handlers (ADR 0015 D5). Runs on the caller's
     * thread: the sync waits for it before recomputing.
     */
    public ImportStats runSync(long batchId, long athleteId, com.fasterxml.jackson.databind.JsonNode payload) {
        var stats = new ImportStats();
        try {
            batches.markRunning(batchId);
            var progress = progressFor(batchId);
            var ctx = new RawWriter.Ctx(athleteId, batchId, stats, progress);
            var zone = athletes.zoneOf(athleteId);
            tx.executeWithoutResult(status -> garmin.importSync(ctx, payload, zone));
            progress.flush();
            batches.markFinished(batchId, stats);
            log.info("Import batch {} (GARMIN_SYNC) finished: {} rows inserted", batchId, stats.insertedTotal());
            return stats;
        } catch (RuntimeException e) {
            log.warn("Import batch {} (GARMIN_SYNC) failed: {}", batchId, e.toString());
            String message = safeMessage(e);
            batches.markFailed(batchId, message, stats);
            throw new SyncImportFailed(message);
        }
    }

    /**
     * A dry run of a sync import (the "Check against export", ADR 0015 D5): the batch and every
     * row are written inside one transaction that is always rolled back, after {@code inspect}
     * has looked at what this batch would add. Nothing remains in the database.
     */
    public <T> T dryRunSync(long athleteId, com.fasterxml.jackson.databind.JsonNode payload,
                            java.util.function.BiFunction<Long, ImportStats, T> inspect) {
        return tx.execute(status -> {
            status.setRollbackOnly();
            var stats = new ImportStats();
            long batchId = batches.create(athleteId, ImportKind.GARMIN_SYNC, "check (not kept)", null, null);
            garmin.importSync(new RawWriter.Ctx(athleteId, batchId, stats), payload, athletes.zoneOf(athleteId));
            return inspect.apply(batchId, stats);
        });
    }

    private static String safeMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return root.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 500 ? m.substring(0, 500) : m));
    }
}
