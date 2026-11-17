package app.training.metrics;

import app.training.common.ApiException;
import app.training.athletes.AthleteRepository;
import app.training.metrics.holdout.HoldoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What "Recompute features" does, in one place: activity loads and day features for the whole
 * history, then the ACTIVE model's daily scores, in one transaction, logged for the live
 * comparison (ADR 0013 D6). The button and the Garmin sync both call this, so a synced day is
 * scored exactly like an imported one (ADR 0015 D7).
 */
@Service
public class FeatureRecomputeService {
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_SYNC = "garmin-sync";

    public record RecomputeResult(ActivityLoadService.Outcome load, DayFeatureService.Outcome features, ScoringService.Outcome scores) {}

    private final AthleteRepository athletes;
    private final ActivityLoadService loads;
    private final DayFeatureService features;
    private final MetricsProperties props;
    private final ScoringService scoring;
    private final HoldoutRepository holdoutLog;

    public FeatureRecomputeService(AthleteRepository athletes, ActivityLoadService loads, DayFeatureService features,
                                   MetricsProperties props, ScoringService scoring, HoldoutRepository holdoutLog) {
        this.athletes = athletes;
        this.loads = loads;
        this.features = features;
        this.props = props;
        this.scoring = scoring;
        this.holdoutLog = holdoutLog;
    }

    /**
     * Without a usable ACTIVE model the features are still recomputed and {@code scores.skipped}
     * says why there are no scores.
     *
     * @param userId who pressed the button; null for a sync
     * @param source {@link #SOURCE_MANUAL} or {@link #SOURCE_SYNC}
     */
    @Transactional
    public RecomputeResult recompute(long athleteId, Long userId, String source) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var load = loads.recompute(athlete);
        var p = loads.params(athlete, loads.restingHr(athlete));
        var bgParams = p == null ? null : p.withMaxGap(props.monitoringMaxGapSeconds());
        var days = features.recompute(athleteId, bgParams, java.time.ZoneId.of(athlete.timezone()));
        var scores = scoring.recomputeIfPossible(athleteId);
        // ADR 0009 H8: the live comparison marks the coach's runs inside its window (ADR 0013 D6, ADR 0015 D8).
        holdoutLog.logRecompute(athleteId, userId, source, props.featureVersion(), days.days(), scores.modelVersion(), scores.days());
        return new RecomputeResult(load, days, scores);
    }
}
