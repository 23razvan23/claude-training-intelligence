package app.training.adaptation.engine;

import app.training.common.ApiException;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The engine's suggestions (stage 2-3, ADR 0022). Reading follows AccessPolicy; running the engine is coach only. */
@RestController
@RequestMapping("/api/athletes/{athleteId}/suggestions")
public class SuggestionController {
    /** Longest range one read may ask for; a display limit, not a training number. */
    static final long MAX_RANGE_DAYS = 400;
    static final Set<String> STATUSES = Set.of("PENDING", "ACCEPTED", "REJECTED", "EDITED", "EXPIRED", "ALL");

    private final SuggestionService suggestions;
    private final AccessPolicy access;

    public SuggestionController(SuggestionService suggestions, AccessPolicy access) {
        this.suggestions = suggestions;
        this.access = access;
    }

    /** Suggestions about days between {@code from} and {@code to} (default: today and the next 13 days), PENDING by default. */
    @GetMapping
    public List<SuggestionRepository.Suggestion> list(@PathVariable long athleteId,
                                                      @RequestParam(defaultValue = "PENDING") String status,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        String st = status.toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(st)) throw ApiException.badRequest("Unknown status '" + status + "'");
        LocalDate start = from != null ? from : suggestions.today(athleteId);
        LocalDate end = to != null ? to : start.plusDays(13);
        if (start.isAfter(end)) throw ApiException.badRequest("'from' is after 'to'");
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) throw ApiException.badRequest("At most " + MAX_RANGE_DAYS + " days per request");
        return suggestions.list(athleteId, "ALL".equals(st) ? null : st, start, end);
    }

    /** The coach's "Check the plan now" button: fresh signals, then the engine. */
    @PostMapping("/run")
    @PreAuthorize("hasRole('COACH')")
    public SuggestionService.RunOutcome run(@PathVariable long athleteId) {
        return suggestions.runWithFreshSignals(athleteId);
    }
}
