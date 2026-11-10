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

    public MetricsController(AthleteRepository athletes, ActivityLoadService loads, DayFeatureService features,
                             MetricsRepository repo, MetricsProperties props, AccessPolicy access) {
        this.athletes = athletes;
        this.loads = loads;
        this.features = features;
        this.repo = repo;
        this.props = props;
        this.access = access;
    }

    public record RecomputeResult(ActivityLoadService.Outcome load, DayFeatureService.Outcome features) {}

    /**
     * Recomputes activity loads and day features for the athlete's whole history, in one
     * transaction. Needed after an import or a profile change (HRmax, HRrest, sex).
     */
    @PostMapping("/athletes/{athleteId}/features/recompute")
    @PreAuthorize("hasRole('COACH')")
    @Transactional
    public RecomputeResult recompute(@PathVariable long athleteId) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var load = loads.recompute(athlete);
        var p = loads.params(athlete, loads.restingHr(athlete));
        var bgParams = p == null ? null
                : new Trimp.Params(p.hrMax(), p.hrRest(), p.k1(), p.k2(), p.edwardsFloors(), props.monitoringMaxGapSeconds());
        var days = features.recompute(athleteId, bgParams, java.time.ZoneId.of(athlete.timezone()));
        return new RecomputeResult(load, days);
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
