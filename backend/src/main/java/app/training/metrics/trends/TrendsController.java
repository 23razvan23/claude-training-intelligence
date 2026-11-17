package app.training.metrics.trends;

import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The Trends screen: an athlete sees only their own, the coach anyone's (AccessPolicy). */
@RestController
public class TrendsController {
    private final TrendsService trends;
    private final AccessPolicy access;

    public TrendsController(TrendsService trends, AccessPolicy access) {
        this.trends = trends;
        this.access = access;
    }

    @GetMapping("/api/athletes/{athleteId}/trends")
    public TrendsView.Trends trends(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return trends.trends(athleteId);
    }
}
