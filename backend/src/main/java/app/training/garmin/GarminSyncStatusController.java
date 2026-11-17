package app.training.garmin;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.garmin.GarminConnectionService.State;
import app.training.garmin.GarminSyncRunRepository.Status;
import app.training.garmin.GarminSyncRunRepository.Trigger;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "Last sync" line on Today and Imports (ADR 0015 D9). Read-only, and readable by an athlete
 * for their own data (AccessPolicy), so Today can say why today's data is missing: the sync
 * failed, Garmin needs a reconnect, or Garmin had no data yet. Fixed codes and times only.
 */
@RestController
public class GarminSyncStatusController {
    /**
     * @param connection the Garmin connection state (the coach reconnects on Imports)
     * @param lastSuccessAt when the latest successful sync finished (null: never)
     * @param lastSuccessThrough the last day that sync fetched
     * @param latestStatus the newest run's status (null: no run yet); newer than the last success when it failed
     * @param latestErrorCode the newest run's fixed error code, if any
     * @param latestAt when the newest run finished (or started, while running)
     * @param nextRetryAt when a waiting run tries again
     * @param latestTrigger MANUAL (Sync now) or MORNING
     * @param morningEnabled whether the morning run is on
     * @param morningTime when the morning run starts, in the athlete's timezone
     */
    public record SyncStatus(State connection, Instant lastSuccessAt, LocalDate lastSuccessThrough, Status latestStatus,
                             String latestErrorCode, Instant latestAt, Instant nextRetryAt, Trigger latestTrigger,
                             boolean morningEnabled, LocalTime morningTime) {}

    private final GarminConnectionService connection;
    private final GarminSyncRunRepository runs;
    private final GarminSyncProperties props;
    private final AccessPolicy access;
    private final AthleteRepository athletes;

    public GarminSyncStatusController(GarminConnectionService connection, GarminSyncRunRepository runs, GarminSyncProperties props,
                                      AccessPolicy access, AthleteRepository athletes) {
        this.connection = connection;
        this.runs = runs;
        this.props = props;
        this.access = access;
        this.athletes = athletes;
    }

    @GetMapping("/api/athletes/{athleteId}/garmin/sync/status")
    public SyncStatus status(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var ok = runs.lastSucceeded(athleteId);
        var latest = runs.recent(athleteId, 1).stream().findFirst();
        return new SyncStatus(connection.status(athleteId).state(),
                ok.map(r -> r.finishedAt()).orElse(null), ok.map(r -> r.lastDay()).orElse(null),
                latest.map(r -> r.status()).orElse(null), latest.map(r -> r.errorCode()).orElse(null),
                latest.map(r -> r.finishedAt() != null ? r.finishedAt() : r.startedAt()).orElse(null),
                latest.map(r -> r.nextRetryAt()).orElse(null), latest.map(r -> r.trigger()).orElse(null),
                props.morningEnabled(), props.morningTime());
    }
}
