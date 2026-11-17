package app.training.garmin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background work of the Garmin sync: retries of runs that wait (ADR 0015 D6); the morning run
 * arrives in PR 3. Off for command-line runs (scoring) and when app.garmin.sync.scheduler-enabled
 * is false (tests call the service directly).
 */
@Configuration
@EnableScheduling
public class GarminSyncScheduler {
    private final GarminSyncService sync;
    private final GarminSyncRunRepository runs;
    private final boolean enabled;

    public GarminSyncScheduler(GarminSyncService sync, GarminSyncRunRepository runs, GarminSyncProperties props,
                               @Value("${app.command-line:false}") boolean commandLine) {
        this.sync = sync;
        this.runs = runs;
        this.enabled = props.schedulerEnabled() && !commandLine;
    }

    /** A run left RUNNING by a restart cannot finish; mark it, like interrupted imports (ADR 0006). */
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (enabled) runs.failInterrupted(java.time.Instant.now());
    }

    // Every minute: retry waits are 10 and 30 minutes, so a minute late is fine. [configurable]
    @Scheduled(fixedDelayString = "${app.garmin.sync.poll-ms:60000}", initialDelayString = "${app.garmin.sync.poll-ms:60000}")
    void tick() {
        if (enabled) sync.retryDue();
    }
}
