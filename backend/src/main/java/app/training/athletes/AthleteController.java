package app.training.athletes;

import app.training.common.ApiException;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/athletes")
public class AthleteController {
    private final AthleteRepository athletes;
    private final AccessPolicy access;

    public AthleteController(AthleteRepository athletes, AccessPolicy access) {
        this.athletes = athletes;
        this.access = access;
    }

    /** Coach: every athlete. Athlete: only themselves. */
    @GetMapping
    public List<Athlete> list(@AuthenticationPrincipal CurrentUser user) {
        if (user.isCoach()) return athletes.findAll();
        return athletes.findById(user.athleteId()).stream().toList();
    }

    @GetMapping("/{id}")
    public Athlete get(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, id);
        return athletes.findById(id).orElseThrow(() -> ApiException.notFound("Athlete"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('COACH')")
    public Athlete create(@Valid @RequestBody AthleteRequest req) {
        validateZone(req);
        long id = athletes.insert(req);
        return athletes.findById(id).orElseThrow();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COACH')")
    public Athlete update(@PathVariable long id, @Valid @RequestBody AthleteRequest req) {
        validateZone(req);
        if (!athletes.update(id, req)) throw ApiException.notFound("Athlete");
        return athletes.findById(id).orElseThrow();
    }

    /** Deletes the athlete and, by cascade, all their data (the spec's per-athlete delete). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('COACH')")
    public void delete(@PathVariable long id) {
        if (!athletes.delete(id)) throw ApiException.notFound("Athlete");
    }

    private static void validateZone(AthleteRequest req) {
        if (req.timezone() == null || req.timezone().isBlank()) return;
        try {
            ZoneId.of(req.timezone());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Unknown timezone: " + req.timezone());
        }
    }
}
