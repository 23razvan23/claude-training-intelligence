package app.training.ingestion;

import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/athletes/{athleteId}/coverage")
public class CoverageController {
    private final CoverageService coverage;
    private final AccessPolicy access;

    public CoverageController(CoverageService coverage, AccessPolicy access) {
        this.coverage = coverage;
        this.access = access;
    }

    /** Defaults to the full span of imported data (capped at three years). */
    @GetMapping
    public CoverageService.Report get(
            @PathVariable long athleteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return coverage.report(athleteId, from, to);
    }
}
