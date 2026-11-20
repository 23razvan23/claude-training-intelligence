package app.training.adaptation.checkin;

import app.training.adaptation.engine.SuggestionService;
import app.training.adaptation.signals.SignalService;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Check-ins: athletes log and read their own, the coach anyone's (AccessPolicy). There is no update or delete. */
@RestController
public class CheckinController {
    private final CheckinService checkins;
    private final AccessPolicy access;
    private final SignalService signals;
    private final SuggestionService suggestions;

    public CheckinController(CheckinService checkins, AccessPolicy access, SignalService signals, SuggestionService suggestions) {
        this.suggestions = suggestions;
        this.checkins = checkins;
        this.access = access;
        this.signals = signals;
    }

    @GetMapping("/api/athletes/{athleteId}/checkins/options")
    public CheckinService.Options options(@PathVariable long athleteId, @RequestParam(required = false) LocalDate date,
                                          @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return checkins.options(athleteId, date);
    }

    @PostMapping("/api/athletes/{athleteId}/checkins")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckinView create(@PathVariable long athleteId, @RequestBody CheckinRequest request,
                              @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        var saved = checkins.create(athleteId, request, user);
        // Stage 2-2 (ADR 0021 D8): the check-in flags and running tolerance follow the new answer; a failure there
        // is logged and never loses the check-in, which is already saved.
        signals.recomputeQuietly(athleteId);
        // Stage 2-3 (ADR 0022): an illness or niggle reported now updates the suggestions at once.
        suggestions.runQuietly(athleteId);
        return saved;
    }

    @GetMapping("/api/athletes/{athleteId}/checkins/recent")
    public List<CheckinView> recent(@PathVariable long athleteId, @RequestParam(defaultValue = "14") int days,
                                    @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return checkins.recent(athleteId, Math.min(Math.max(days, 1), 90));
    }

    /** The coach's overview: the current check-ins of every athlete. */
    @GetMapping("/api/checkins/recent")
    @PreAuthorize("hasRole('COACH')")
    public List<CheckinView> recentForAll(@RequestParam(defaultValue = "14") int days) {
        return checkins.recentForAll(Math.min(Math.max(days, 1), 90));
    }
}
