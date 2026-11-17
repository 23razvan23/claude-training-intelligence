package app.training.garmin;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.garmin.GarminSyncRunRepository.Run;
import app.training.garmin.GarminSyncRunRepository.Trigger;
import app.training.ingestion.ImportBatchRepository;
import app.training.ingestion.ImportKind;
import app.training.ingestion.ImportRunner;
import app.training.metrics.FeatureRecomputeService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * One Garmin sync (ADR 0015 D5-D7): fetch today and the previous days through the sidecar,
 * import them through the export's own path (same raw tables, dedup and revisions), then run
 * the same recompute as the Recompute features button if anything new arrived.
 *
 * <p>Garmin unavailable or rate limited: the run waits and is retried (D6). Garmin rejecting the
 * token: no retry, the credential becomes RECONNECT_NEEDED (ADR 0011 D6). Every failure is
 * stored and logged as a fixed code only.
 */
@Service
public class GarminSyncService {
    private static final Logger log = LoggerFactory.getLogger(GarminSyncService.class);

    /** Failures worth trying again later; anything else needs the coach. */
    static final Set<String> TRANSIENT = Set.of("GARMIN_UNAVAILABLE", "RATE_LIMITED", "SIDECAR_UNREACHABLE", "IMPORT_RUNNING");

    /** A failure with a fixed code (never a Garmin payload or secret). */
    static final class SyncFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        SyncFailure(String code) {
            super(code);
            this.code = code;
        }
    }

    private final GarminSetup setup;
    private final GarminCredentialRepository credentials;
    private final GarminSyncRunRepository runs;
    private final SidecarClient sidecar;
    private final ImportBatchRepository batches;
    private final ImportRunner runner;
    private final FeatureRecomputeService recompute;
    private final AthleteRepository athletes;
    private final GarminSyncProperties props;
    private final Clock clock;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public GarminSyncService(GarminSetup setup, GarminCredentialRepository credentials, GarminSyncRunRepository runs,
                             SidecarClient sidecar, ImportBatchRepository batches, ImportRunner runner,
                             FeatureRecomputeService recompute, AthleteRepository athletes, GarminSyncProperties props, Clock clock) {
        this.setup = setup;
        this.credentials = credentials;
        this.runs = runs;
        this.sidecar = sidecar;
        this.batches = batches;
        this.runner = runner;
        this.recompute = recompute;
        this.athletes = athletes;
        this.props = props;
        this.clock = clock;
    }

    /** Today in the athlete's timezone (ADR 0014 D1), from the server clock. */
    public LocalDate today(long athleteId) {
        return LocalDate.now(clock.withZone(athletes.zoneOf(athleteId)));
    }

    /** Sync now: starts a run in the background and returns it at once (the UI polls the run list). */
    public Run start(long athleteId, Trigger trigger) {
        long id = begin(athleteId, trigger, today(athleteId), Instant.now(clock));
        background.execute(() -> execute(id));
        return runs.find(id).orElseThrow();
    }

    /** The same, on the caller's thread (tests). */
    public Run runNow(long athleteId, Trigger trigger, LocalDate today) {
        long id = begin(athleteId, trigger, today, Instant.now(clock));
        execute(id);
        return runs.find(id).orElseThrow();
    }

    /**
     * The morning run (ADR 0015 D9), checked every minute by GarminSyncScheduler: for each
     * connected athlete whose morning run is due ({@link MorningSchedule}), start one in the
     * background. Because the check runs every minute, a Mac that was asleep at 07:00 starts the
     * run within a minute of waking. An athlete who can't sync right now (key changed, a run
     * already going) is skipped quietly and looked at again next minute.
     */
    public void morningDue() {
        morning(Instant.now(clock), true);
    }

    /** The same on the caller's thread with a given "now" (tests with a fake clock); returns the runs started. */
    List<Run> morningDueNow(Instant now) {
        return morning(now, false);
    }

    private List<Run> morning(Instant now, boolean inBackground) {
        var started = new ArrayList<Run>();
        if (!props.morningEnabled() || !setup.enabled()) return started;
        for (long athleteId : credentials.connectedAthletes()) {
            var zone = athletes.zoneOf(athleteId);
            if (!MorningSchedule.due(now, zone, props.morningTime(), runs.lastMorningCounting(athleteId).orElse(null))) continue;
            long id;
            try {
                id = begin(athleteId, Trigger.MORNING, now.atZone(zone).toLocalDate(), now);
            } catch (ApiException e) {
                continue; // not syncable now (reconnect needed, a run already going): try again next minute
            }
            log.info("Garmin morning sync run {} started for athlete {}", id, athleteId);
            if (inBackground) {
                background.execute(() -> execute(id));
                started.add(runs.find(id).orElseThrow());
            } else {
                execute(id);
                started.add(runs.find(id).orElseThrow());
            }
        }
        return started;
    }

    private long begin(long athleteId, Trigger trigger, LocalDate today, Instant now) {
        requireConnected(athleteId);
        if (trigger == Trigger.MANUAL) runs.supersedeWaiting(athleteId, now); // Sync now replaces a run that waits to retry
        return runs.start(athleteId, trigger, today.minusDays(props.refetchDays()), today, now)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "A Garmin sync is already running", "SYNC_RUNNING"));
    }

    /** Runs whose retry time has come (called every minute by GarminSyncScheduler). */
    public void retryDue() {
        Instant now = Instant.now(clock);
        for (long id : runs.dueRetries(now)) {
            if (runs.claimRetry(id, now)) background.execute(() -> execute(id));
        }
    }

    /** Same as retryDue, on the caller's thread (tests). */
    void retryDueNow() {
        Instant now = Instant.now(clock);
        for (long id : runs.dueRetries(now)) {
            if (runs.claimRetry(id, now)) execute(id);
        }
    }

    void execute(long runId) {
        Run run = runs.find(runId).orElseThrow();
        long athleteId = run.athleteId();
        Long batchId = null;
        Long inserted = null;
        try {
            String tokens = tokens(athleteId);
            JsonNode payload;
            try {
                payload = sidecar.fetchDays(tokens, days(run.firstDay(), run.lastDay()), props.timeoutSeconds());
            } catch (SidecarClient.SidecarException e) {
                throw new SyncFailure("BAD_SECRET".equals(e.code()) || "NOT_CONFIGURED".equals(e.code()) ? "SECRET_MISMATCH" : e.code());
            }
            storeRefreshed(athleteId, tokens, payload.get("tokens").asText());
            if (batches.hasActive(athleteId)) throw new SyncFailure("IMPORT_RUNNING");
            batchId = batches.create(athleteId, ImportKind.GARMIN_SYNC,
                    "Garmin sync " + run.firstDay() + " to " + run.lastDay(), null, null);
            try {
                inserted = runner.runSync(batchId, athleteId, payload).insertedTotal();
            } catch (ImportRunner.SyncImportFailed e) {
                throw new SyncFailure("IMPORT_FAILED");
            }
            boolean recomputed = false;
            if (inserted > 0) {
                // Nothing new means nothing to recompute: a second sync of the same morning changes no score.
                try {
                    recompute.recompute(athleteId, null, FeatureRecomputeService.SOURCE_SYNC);
                    recomputed = true;
                } catch (RuntimeException e) {
                    log.warn("Garmin sync run {}: recompute failed: {}", runId, e.getClass().getSimpleName());
                    throw new SyncFailure("RECOMPUTE_FAILED");
                }
            }
            runs.succeed(runId, batchId, inserted, recomputed, missing(payload), Instant.now(clock));
            log.info("Garmin sync run {} for athlete {} ({} to {}): {} rows inserted{}", runId, athleteId, run.firstDay(),
                    run.lastDay(), inserted, recomputed ? ", features and scores recomputed" : "");
        } catch (SyncFailure f) {
            fail(run, f.code, batchId, inserted);
        } catch (RuntimeException e) {
            log.warn("Garmin sync run {}: unexpected {}", runId, e.getClass().getSimpleName());
            fail(run, "SYNC_ERROR", batchId, inserted);
        }
    }

    private void fail(Run run, String code, Long batchId, Long inserted) {
        Instant now = Instant.now(clock);
        if ("AUTH_REJECTED".equals(code) || GarminConnectionService.KEY_CHANGED.equals(code)) {
            // ADR 0011 D6/D7: stop until the coach connects again; never retry a rejected token.
            credentials.markReconnectNeeded(run.athleteId(), code, now);
        }
        List<Integer> delays = props.retryDelaysMinutes() == null ? List.of() : props.retryDelaysMinutes();
        if (TRANSIENT.contains(code) && run.attempt() <= delays.size()) {
            Instant at = now.plus(Duration.ofMinutes(delays.get(run.attempt() - 1)));
            runs.retryLater(run.id(), code, at);
            log.warn("Garmin sync run {} attempt {} failed: {}; retrying at {}", run.id(), run.attempt(), code, at);
        } else {
            runs.fail(run.id(), code, batchId, inserted, now);
            log.warn("Garmin sync run {} attempt {} failed: {}", run.id(), run.attempt(), code);
        }
    }

    /** The decrypted token bundle, or a failure that says why there is none. Never logged. */
    String tokens(long athleteId) {
        if (!setup.enabled()) throw new SyncFailure("NOT_CONFIGURED");
        var row = credentials.find(athleteId).orElseThrow(() -> new SyncFailure("NOT_CONNECTED"));
        if ("RECONNECT_NEEDED".equals(row.status())) throw new SyncFailure("RECONNECT_NEEDED");
        var cipher = setup.cipher().orElseThrow();
        if (!cipher.keyId().equals(row.keyId())) throw new SyncFailure(GarminConnectionService.KEY_CHANGED);
        try {
            return new String(cipher.decrypt(athleteId, row.tokenCiphertext()), StandardCharsets.UTF_8);
        } catch (TokenCipher.DecryptFailed e) {
            throw new SyncFailure(GarminConnectionService.KEY_CHANGED);
        }
    }

    /** Garmin renewed the token during the fetch: keep the new one (ADR 0011 D4, D6). */
    void storeRefreshed(long athleteId, String sent, String returned) {
        if (returned == null || returned.isBlank() || returned.equals(sent)) return;
        var cipher = setup.cipher().orElseThrow();
        credentials.saveRefreshed(athleteId, cipher.encrypt(athleteId, returned.getBytes(StandardCharsets.UTF_8)), cipher.keyId(),
                Instant.now(clock));
    }

    /** Refuses before a run is even recorded when sync cannot work for this athlete. */
    void requireConnected(long athleteId) {
        try {
            tokens(athleteId);
        } catch (SyncFailure f) {
            HttpStatus status = "NOT_CONFIGURED".equals(f.code) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
            String message = switch (f.code) {
                case "NOT_CONFIGURED" -> "Garmin sync is not configured";
                case "NOT_CONNECTED" -> "Garmin is not connected";
                default -> "Garmin needs you to connect again";
            };
            throw new ApiException(status, message, f.code);
        }
    }

    static List<LocalDate> days(LocalDate first, LocalDate last) {
        var out = new ArrayList<LocalDate>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) out.add(d);
        return out;
    }

    private static List<String> missing(JsonNode payload) {
        var out = new ArrayList<String>();
        for (JsonNode m : payload.path("missing")) {
            String s = m.asText("");
            if (s.matches("[A-Za-z]+:[0-9A-Za-z-]{1,40}")) out.add(s); // labels only, never free text
        }
        return out;
    }
}
