package app.training.garmin;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.garmin.GarminSyncRunRepository.Run;
import app.training.garmin.GarminSyncRunRepository.Trigger;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Sync now, the run history and the check against an export (ADR 0015), coach only. */
@RestController
@RequestMapping("/api/athletes/{athleteId}/garmin")
@PreAuthorize("hasRole('COACH')")
public class GarminSyncController {
    public record CheckRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}

    private final GarminSyncService sync;
    private final GarminSyncCheckService check;
    private final GarminSyncRunRepository runs;
    private final AccessPolicy access;
    private final AthleteRepository athletes;

    public GarminSyncController(GarminSyncService sync, GarminSyncCheckService check, GarminSyncRunRepository runs,
                                AccessPolicy access, AthleteRepository athletes) {
        this.sync = sync;
        this.check = check;
        this.runs = runs;
        this.access = access;
        this.athletes = athletes;
    }

    /** Starts a sync of today and the previous days; answers at once with the run (202). */
    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Run syncNow(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        requireAthlete(user, athleteId);
        return sync.start(athleteId, Trigger.MANUAL);
    }

    /** The latest runs, newest first. */
    @GetMapping("/sync/runs")
    public List<Run> runs(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        requireAthlete(user, athleteId);
        return runs.recent(athleteId, 10);
    }

    /** A dry run on days already imported from an export; nothing is kept. */
    @PostMapping("/check")
    public GarminSyncCheckService.Report check(@PathVariable long athleteId, @Valid @RequestBody CheckRequest req,
                                               @AuthenticationPrincipal CurrentUser user) {
        requireAthlete(user, athleteId);
        return check.check(athleteId, req.from(), req.to());
    }

    private void requireAthlete(CurrentUser user, long athleteId) {
        access.requireAthlete(user, athleteId);
        athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
    }
}
