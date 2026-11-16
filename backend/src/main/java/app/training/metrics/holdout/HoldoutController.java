package app.training.metrics.holdout;

import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /**
     * The decision report: self-contained HTML, downloaded as a file (ADR 0013 D7). Served with
     * a CSP that forbids scripts and remote loads, like the calibration report.
     */
    @GetMapping("/report")
    public ResponseEntity<String> decisionReport(@PathVariable long athleteId, @RequestParam(required = false) String version,
                                                 @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        var file = holdout.decisionReport(athleteId, version);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.html());
    }

    /** What has arrived (last Whoop and Garmin days, last imports) and whether Recompute features is needed. */
    @GetMapping("/feed")
    public HoldoutService.Feed feed(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return holdout.feed(athleteId);
    }

    /** Every version that has (or had) a live comparison, newest first. */
    @GetMapping("/versions")
    public List<HoldoutService.VersionSummary> versions(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return holdout.versions(athleteId);
    }
}
