package app.training.metrics;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MetricsController {
    private final AthleteRepository athletes;
    private final ActivityLoadService loads;
    private final DayFeatureService features;
    private final MetricsRepository repo;
    private final MetricsProperties props;
    private final AccessPolicy access;
    private final ScoringService scoring;

    public MetricsController(AthleteRepository athletes, ActivityLoadService loads, DayFeatureService features,
                             MetricsRepository repo, MetricsProperties props, AccessPolicy access, ScoringService scoring) {
        this.scoring = scoring;
        this.athletes = athletes;
        this.loads = loads;
        this.features = features;
        this.repo = repo;
        this.props = props;
        this.access = access;
    }

    public record RecomputeResult(ActivityLoadService.Outcome load, DayFeatureService.Outcome features, ScoringService.Outcome scores) {}

    /**
     * Recomputes activity loads and day features for the athlete's whole history, then the daily
     * scores from the ACTIVE model, in one transaction. Needed after an import or a profile change
     * (HRmax, HRrest, sex). Without a usable ACTIVE model the features are still recomputed and
     * {@code scores.skipped} says why there are no scores.
     */
    @PostMapping("/athletes/{athleteId}/features/recompute")
    @PreAuthorize("hasRole('COACH')")
    @Transactional
    public RecomputeResult recompute(@PathVariable long athleteId) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var load = loads.recompute(athlete);
        var p = loads.params(athlete, loads.restingHr(athlete));
        var bgParams = p == null ? null : p.withMaxGap(props.monitoringMaxGapSeconds());
        var days = features.recompute(athleteId, bgParams, java.time.ZoneId.of(athlete.timezone()));
        return new RecomputeResult(load, days, scoring.recomputeIfPossible(athleteId));
    }

    @GetMapping("/athletes/{athleteId}/features/summary")
    public MetricsRepository.FeatureSummary summary(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return repo.summary(athleteId, props.featureVersion());
    }

    @GetMapping("/athletes/{athleteId}/models")
    public List<ModelVersion> models(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return repo.models(athleteId);
    }

    @GetMapping("/models/{id}")
    public ModelVersion model(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        var m = repo.model(id).orElseThrow(() -> ApiException.notFound("Model"));
        access.requireAthlete(user, m.athleteId());
        return m;
    }

    /**
     * Freezes a calibrated version as the athlete's ACTIVE model: the one daily scoring and
     * the live comparison with Whoop will use, from its activation time. Only versions that
     * met their targets and that the daily scoring can read qualify (ADR 0007 item 8).
     * Activating the version that is already ACTIVE changes nothing, so its activation time
     * (the start of the live comparison) cannot move by accident.
     */
    @PostMapping("/models/{id}/activate")
    @PreAuthorize("hasRole('COACH')")
    @Transactional
    public ModelVersion activate(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        var m = repo.model(id).orElseThrow(() -> ApiException.notFound("Model"));
        if ("ACTIVE".equals(m.status())) {
            return m;
        }
        if (!m.passed()) {
            throw conflict("Only a model version that met its acceptance targets can be activated.");
        }
        if ("RETIRED".equals(m.status())) {
            throw conflict("This version was retired (for example, fitted on corrected data). Run a new calibration instead.");
        }
        if (m.olderThanActive()) {
            var active = repo.active(m.athleteId()).map(ModelVersion::version).orElse("another version");
            throw conflict("A newer version (" + active + ") is already active. An older version can't replace it: "
                    + "run a new calibration instead.");
        }
        if (m.contractIssue() == ModelVersion.ContractIssue.FEATURE_VERSION) {
            throw conflict("This version was built on feature set " + m.featureVersion() + ", but the app now computes "
                    + props.featureVersion() + ". Press Recompute features, run a new calibration, and activate that version.");
        }
        if (m.contractIssue() == ModelVersion.ContractIssue.SCHEMA_VERSION) {
            throw conflict("This version was made by an older calibration (before 0.6.0) whose output the daily scoring "
                    + "can't read. Run a new calibration and activate that version.");
        }
        repo.activate(m, user.id());
        return repo.model(id).orElseThrow();
    }

    private static ApiException conflict(String message) {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, message);
    }

    /**
     * The calibration report: self-contained HTML written by the calibration job. Served
     * with a CSP that forbids scripts and remote loads; the UI shows it in a sandboxed iframe.
     */
    @GetMapping("/models/{id}/report")
    public ResponseEntity<String> report(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        var m = repo.model(id).orElseThrow(() -> ApiException.notFound("Model"));
        access.requireAthlete(user, m.athleteId());
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(repo.report(id).orElse(""));
    }
}
