package app.training.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.IntegrationTest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression test for the first real all-day-HR import, which hung forever: inserting
 * into a new TimescaleDB chunk of monitoring_hr copied its foreign key to import_batch
 * onto the chunk, which locks import_batch until the import commits. The progress update
 * (its own transaction, writing import_batch) then waited on the import, and the import
 * waited on the progress update.
 */
class ProgressDeadlockIT extends IntegrationTest {
    @Autowired ImportRunner runner;
    @Autowired ImportBatchRepository batches;
    @Autowired TransactionTemplate tx;

    private long batch(long athlete) {
        long coach = jdbc.sql("INSERT INTO app_user (username, password_hash, role) VALUES ('c' || nextval('app_user_id_seq'), 'x', 'COACH') RETURNING id")
                .query(Long.class).single();
        return batches.create(athlete, ImportKind.GARMIN_EXPORT, "t", null, coach);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void progressDoesNotWaitForAnImportThatCreatesANewChunk() {
        long athlete = newAthlete("Chunk");
        long batchId = batch(athlete);
        tx.executeWithoutResult(status -> {
            // A month no other test uses: forces TimescaleDB to create a new chunk inside this transaction.
            jdbc.sql("INSERT INTO monitoring_hr (athlete_id, ts, hr, import_batch_id) VALUES (?, '2031-07-15T10:00:00Z', 60, ?)")
                    .params(athlete, batchId).update();
            var progress = runner.progressFor(batchId);
            progress.phase("fit", null);
            progress.flush();
        });
        assertThat(batches.find(batchId).orElseThrow().progress().path("phase").asText()).isEqualTo("fit");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void progressIsBestEffortWhenImportBatchIsLocked() {
        long athlete = newAthlete("Locked");
        long batchId = batch(athlete);
        tx.executeWithoutResult(status -> {
            // Any future lock on import_batch held by the import must not stall it either.
            jdbc.sql("LOCK TABLE import_batch IN SHARE ROW EXCLUSIVE MODE").update();
            var progress = runner.progressFor(batchId);
            progress.phase("fit", null);
            progress.step();
            progress.flush(); // must return: the update is skipped, the import carries on
        });
    }
}
