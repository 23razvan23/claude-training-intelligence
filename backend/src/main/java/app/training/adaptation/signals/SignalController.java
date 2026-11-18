package app.training.adaptation.signals;

import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Signals per day (stage 2-2, ADR 0021). Reading follows AccessPolicy; recomputing is coach only. */
@RestController
@RequestMapping("/api/athletes/{athleteId}/signals")
public class SignalController {
    /** Longest range one read may ask for; a display limit, not a training number. */
    static final long MAX_RANGE_DAYS = 400;

    private final SignalService signals;
    private final AccessPolicy access;

    public SignalController(SignalService signals, AccessPolicy access) {
        this.signals = signals;
        this.access = access;
    }

    /** Stored signals between two days (default: the last 28 days up to today in the athlete's timezone). */
    @GetMapping
    public List<SignalRepository.Stored> read(@PathVariable long athleteId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        LocalDate end = to != null ? to : signals.today(athleteId);
        LocalDate start = from != null ? from : end.minusDays(27);
        if (start.isAfter(end)) throw app.training.common.ApiException.badRequest("'from' is after 'to'");
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS)
            throw app.training.common.ApiException.badRequest("At most " + MAX_RANGE_DAYS + " days per request");
        return signals.read(athleteId, start, end);
    }

    @PostMapping("/recompute")
    @PreAuthorize("hasRole('COACH')")
    public SignalService.Outcome recompute(@PathVariable long athleteId) {
        return signals.recompute(athleteId);
    }
}
