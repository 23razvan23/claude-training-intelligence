package app.training.metrics.today;

import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The Today screen: an athlete sees only their own, the coach anyone's (AccessPolicy). */
@RestController
public class TodayController {
    private final TodayService today;
    private final AccessPolicy access;

    public TodayController(TodayService today, AccessPolicy access) {
        this.today = today;
        this.access = access;
    }

    @GetMapping("/api/athletes/{athleteId}/today")
    public TodayView.Today today(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return today.today(athleteId);
    }
}
