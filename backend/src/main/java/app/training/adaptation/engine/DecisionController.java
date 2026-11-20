package app.training.adaptation.engine;

import app.training.planning.PlanService;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deciding on suggestions and reading them per session and for Today (stage 2-3 PR 2, ADR 0022). Deciding is coach
 * only; reading follows AccessPolicy (an athlete sees their own).
 */
@RestController
@RequestMapping("/api")
public class DecisionController {
    private final DecisionService decisions;
    private final SuggestionService suggestions;
    private final PlanService plans;
    private final AccessPolicy access;

    public DecisionController(DecisionService decisions, SuggestionService suggestions, PlanService plans, AccessPolicy access) {
        this.decisions = decisions;
        this.suggestions = suggestions;
        this.plans = plans;
        this.access = access;
    }

    @PostMapping("/suggestions/{id}/accept")
    @PreAuthorize("hasRole('COACH')")
    public DecisionService.Outcome accept(@PathVariable long id, @RequestBody(required = false) DecisionService.Decision body,
                                          @AuthenticationPrincipal CurrentUser user) {
        return decisions.accept(id, body, user.id());
    }

    @PostMapping("/suggestions/{id}/reject")
    @PreAuthorize("hasRole('COACH')")
    public DecisionService.Outcome reject(@PathVariable long id, @RequestBody(required = false) DecisionService.Decision body,
                                          @AuthenticationPrincipal CurrentUser user) {
        return decisions.reject(id, body, user.id());
    }

    @GetMapping("/sessions/{sessionId}/suggestions")
    public List<SuggestionRepository.Suggestion> forSession(@PathVariable long sessionId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, plans.session(sessionId).athleteId());
        return suggestions.forSession(sessionId);
    }

    @GetMapping("/athletes/{athleteId}/suggestions/today")
    public SuggestionService.TodayView today(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return suggestions.todayView(athleteId);
    }
}
