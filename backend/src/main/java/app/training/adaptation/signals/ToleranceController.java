package app.training.adaptation.signals;

import app.training.common.ApiException;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Running tolerance with its derivation and the coach's override (ADR 0021 D11). Reading follows AccessPolicy;
 * setting or clearing the override is coach only. An override is a new row (append-only); the signals are recomputed
 * right after it is saved.
 */
@RestController
@RequestMapping("/api/athletes/{athleteId}/tolerance")
public class ToleranceController {
    static final int MAX_NOTE = 500;

    private final SignalService signals;
    private final SignalRepository repo;
    private final AccessPolicy access;

    public ToleranceController(SignalService signals, SignalRepository repo, AccessPolicy access) {
        this.signals = signals;
        this.repo = repo;
        this.access = access;
    }

    /** {@code date}: the newest day with signals; {@code tolerance} and {@code running} from that day (null when none yet). */
    public record View(LocalDate date, JsonNode tolerance, JsonNode running, List<SignalRepository.OverrideRow> overrides) {}

    /** {@code kmPerWeek} null clears the override (the computed tolerance counts again). */
    public record OverrideRequest(Double kmPerWeek, String note) {}

    @GetMapping
    public View get(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return view(athleteId);
    }

    @PostMapping("/override")
    @PreAuthorize("hasRole('COACH')")
    public View override(@PathVariable long athleteId, @RequestBody OverrideRequest r, @AuthenticationPrincipal CurrentUser user) {
        if (r == null) throw ApiException.badRequest("No override given");
        if (r.kmPerWeek() != null && (r.kmPerWeek().isNaN() || r.kmPerWeek() < 0 || r.kmPerWeek() > 1000))
            throw ApiException.badRequest("Tolerance must be between 0 and 1000 km per week");
        String note = r.note() == null || r.note().isBlank() ? null : r.note().strip();
        if (note != null && note.length() > MAX_NOTE) throw ApiException.badRequest("Note is longer than " + MAX_NOTE + " characters");
        signals.today(athleteId); // 404 for an unknown athlete before anything is written
        repo.insertOverride(athleteId, r.kmPerWeek() == null ? null : Math.round(r.kmPerWeek() * 10) / 10.0, note, user == null ? null : user.id());
        signals.recompute(athleteId);
        return view(athleteId);
    }

    private View view(long athleteId) {
        LocalDate today = signals.today(athleteId);
        var rows = signals.read(athleteId, today.minusDays(7), today);
        var overrides = repo.overrides(athleteId).reversed();
        if (rows.isEmpty()) return new View(null, null, null, overrides);
        var last = rows.get(rows.size() - 1);
        return new View(last.date(), last.signals().get("tolerance"), last.signals().get("running"), overrides);
    }
}
