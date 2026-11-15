package app.training.metrics;

import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Daily Recovery, Strain and Sleep scores (stage 1b-1). The work is done in {@link ScoringService}. */
@RestController
@RequestMapping("/api/athletes/{athleteId}/scores")
public class ScoreController {
    private final ScoringService scoring;
    private final AccessPolicy access;

    public ScoreController(ScoringService scoring, AccessPolicy access) {
        this.scoring = scoring;
        this.access = access;
    }

    /**
     * Rescores a date range (both ends optional, inclusive) with the athlete's ACTIVE model.
     * 409 when there is no ACTIVE model the app can score with.
     */
    @PostMapping("/recompute")
    @PreAuthorize("hasRole('COACH')")
    public ScoringService.Outcome recompute(
            @PathVariable long athleteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return scoring.recompute(athleteId, from, to);
    }

    /** Stored scores from the ACTIVE model, oldest first. */
    @GetMapping
    public List<ScoringRepository.StoredDay> days(
            @PathVariable long athleteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return scoring.days(athleteId, from, to);
    }
}
