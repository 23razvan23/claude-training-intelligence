package app.training.metrics.holdout;

import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live comparison with Whoop (ADR 0009), coach only. Read-only by design: there is no
 * endpoint to edit the frozen pass marks or to leave a day out (ADR 0009, Consequences).
 */
@RestController
@RequestMapping("/api/athletes/{athleteId}/holdout")
@PreAuthorize("hasRole('COACH')")
public class HoldoutController {
    private final HoldoutService holdout;
    private final AccessPolicy access;

    public HoldoutController(HoldoutService holdout, AccessPolicy access) {
        this.holdout = holdout;
        this.access = access;
    }

    /** The comparison for the ACTIVE version, or for {@code version} (e.g. a retired one, kept as history). */
    @GetMapping
    public HoldoutEvaluator.Report report(@PathVariable long athleteId, @RequestParam(required = false) String version,
                                          @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return holdout.report(athleteId, version);
    }

    /** Every version that has (or had) a live comparison, newest first. */
    @GetMapping("/versions")
    public List<HoldoutService.VersionSummary> versions(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return holdout.versions(athleteId);
    }
}
