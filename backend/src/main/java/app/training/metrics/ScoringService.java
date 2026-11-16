package app.training.metrics;

import app.training.athletes.Athlete;
import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily scoring (stage 1b-1): the athlete's ACTIVE model applied to their day_features, written
 * to derived_day_metrics. The one entry point for the REST endpoint, the command line, the
 * "Recompute features" button and (later) the morning sync.
 *
 * <p>Scores are always computed over the athlete's whole day grid, because a day needs
 * yesterday's strain and the previous week's sleep (ADR 0008 S1); only the requested dates are
 * then replaced. Rows of other model versions are kept as history (ADR 0012).
 */
@Service
public class ScoringService {
    private final AthleteRepository athletes;
    private final MetricsRepository models;
    private final ScoringRepository repo;
    private final ActivityLoadService loads;
    private final MetricsProperties props;

    public ScoringService(AthleteRepository athletes, MetricsRepository models, ScoringRepository repo,
                          ActivityLoadService loads, MetricsProperties props) {
        this.athletes = athletes;
        this.models = models;
        this.repo = repo;
        this.loads = loads;
        this.props = props;
    }

    /**
     * What a recompute did.
     *
     * @param from first date written (null when nothing was)
     * @param lastDataDate the athlete's last day_features date: later days have no score (ADR 0008 C2)
     * @param skipped why nothing was scored, in plain words; null when scoring ran
     */
    public record Outcome(
            String modelVersion, String featureVersion, int days, int withRecovery, int withStrain, int withSleepPerformance,
            int lowerConfidence, int withDrift, int stale, boolean profileChanged, LocalDate from, LocalDate to,
            LocalDate lastDataDate, String skipped) {

        static Outcome skipped(String reason) {
            return new Outcome(null, null, 0, 0, 0, 0, 0, 0, 0, false, null, null, null, reason);
        }
    }

    /** Scoring can't run for this athlete (no usable ACTIVE model); the message says why. */
    public static class Unavailable extends ApiException {
        public Unavailable(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    /**
     * Rescores the athlete with their ACTIVE model and replaces its rows for {@code from}..{@code to}
     * (either may be null: open-ended; both null: all dates).
     *
     * @throws Unavailable when there is no ACTIVE model the app can score with
     */
    @Transactional
    public Outcome recompute(long athleteId, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'from' is after 'to'.");
        }
        Athlete athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        ModelVersion model = models.active(athleteId)
                .orElseThrow(() -> new Unavailable("No ACTIVE model: calibrate and activate a model version first."));
        if (!props.featureVersion().equals(model.featureVersion())) {
            throw new Unavailable("The ACTIVE model " + model.version() + " was built on feature set " + model.featureVersion()
                    + ", but the app computes " + props.featureVersion() + ". Run a new calibration and activate it.");
        }
        ScoringContract contract;
        try {
            contract = ScoringContract.parse(model.coefficients());
        } catch (ScoringContract.Invalid e) {
            throw new Unavailable("The ACTIVE model " + model.version() + " can't be scored with: " + e.getMessage());
        }
        if (!model.featureVersion().equals(contract.featureVersion())) {
            throw new Unavailable("The ACTIVE model " + model.version() + " says feature set " + contract.featureVersion()
                    + " in its coefficients but " + model.featureVersion() + " in its record.");
        }

        var rows = repo.features(athleteId, model.featureVersion());
        var computedAt = new LinkedHashMap<LocalDate, Instant>();
        rows.forEach(r -> computedAt.put(r.input().date(), r.computedAt()));
        List<DailyScorer.DayScore> scores = DailyScorer.score(rows.stream().map(ScoringRepository.FeatureRow::input).toList(), contract);

        Map<String, Object> profile = profile(athlete, contract);
        boolean profileChanged = profile != null;
        Instant lastImport = repo.lastImportFinished(athleteId).orElse(null);

        repo.delete(athleteId, model.version(), from, to);
        var out = new ArrayList<ScoringRepository.Row>();
        int recovery = 0, strain = 0, perf = 0, lower = 0, drift = 0, stale = 0;
        for (var s : scores) {
            if ((from != null && s.date().isBefore(from)) || (to != null && s.date().isAfter(to))) continue;
            Instant at = computedAt.get(s.date());
            boolean isStale = at != null && lastImport != null && at.isBefore(lastImport);
            out.add(row(s, contract, profile, isStale));
            if (s.recovery() != null) recovery++;
            if (s.strain() != null) strain++;
            if (s.sleepPerf() != null) perf++;
            if (s.recoveryLowerConfidence()) lower++;
            if (!s.drift().isEmpty()) drift++;
            if (isStale) stale++;
        }
        repo.insert(athleteId, model.version(), model.featureVersion(), out);
        LocalDate last = scores.isEmpty() ? null : scores.getLast().date();
        return new Outcome(model.version(), model.featureVersion(), out.size(), recovery, strain, perf, lower, drift, stale,
                profileChanged, out.isEmpty() ? null : out.getFirst().date(), out.isEmpty() ? null : out.getLast().date(), last, null);
    }

    /** Like {@link #recompute} for all dates, but reports "skipped" instead of failing (used after Recompute features). */
    @Transactional
    public Outcome recomputeIfPossible(long athleteId) {
        try {
            return recompute(athleteId, null, null);
        } catch (Unavailable e) {
            return Outcome.skipped(e.getMessage());
        }
    }

    /** The athlete's stored scores from their ACTIVE model. */
    public List<ScoringRepository.StoredDay> days(long athleteId, LocalDate from, LocalDate to) {
        var model = models.active(athleteId).orElse(null);
        return model == null ? List.of() : repo.days(athleteId, model.version(), from, to);
    }

    /**
     * The HRmax/HRrest the app would use today versus those the model's features were built
     * with (feature_config). Null when they agree, or when the model doesn't record them.
     */
    Map<String, Object> profile(Athlete athlete, ScoringContract c) {
        Integer hrMax = athlete.hrMax();
        Integer hrRest = loads.restingHr(athlete);
        boolean changed = (c.hrMax() != null && !Objects.equals(c.hrMax(), hrMax))
                || (c.hrRest() != null && !Objects.equals(c.hrRest(), hrRest));
        if (!changed) return null;
        var m = new LinkedHashMap<String, Object>();
        m.put("model_hr_max", c.hrMax());
        m.put("model_hr_rest", c.hrRest());
        m.put("now_hr_max", hrMax);
        m.put("now_hr_rest", hrRest);
        return m;
    }

    private static ScoringRepository.Row row(DailyScorer.DayScore s, ScoringContract c, Map<String, Object> profile, boolean stale) {
        var contributors = new LinkedHashMap<String, Object>();
        contributors.put("intercept", c.recovery().intercept());
        var terms = new LinkedHashMap<String, Object>();
        s.contributions().forEach((feature, points) -> {
            var t = new LinkedHashMap<String, Object>();
            t.put("value", s.inputs().get(feature));
            t.put("points", points);
            terms.put(feature, t);
        });
        contributors.put("terms", terms);
        if (s.recoveryRaw() != null) contributors.put("recovery_raw", s.recoveryRaw());

        var loads = new LinkedHashMap<String, Object>(s.loads());
        if (s.strainRaw() != null) loads.put("strain_raw", s.strainRaw());
        if (s.strain() != null) loads.put("strain", s.strain());
        loads.put("sleep_need", s.sleepNeed());
        if (s.sleepPerf() != null) loads.put("sleep_performance", s.sleepPerf());

        var q = new LinkedHashMap<String, Object>();
        q.put("has_features", s.hasRow());
        if (s.strainReason() != null) q.put("strain_reason", s.strainReason());
        q.put("low_coverage", s.lowCoverage());
        if (s.sleepPerf() == null) q.put("sleep_performance_reason", s.hasRow() ? "NO_SLEEP" : "NO_DAY_DATA");
        q.put("recovery_reasons", s.recoveryReasons());
        q.put("recovery_lower_confidence", s.recoveryLowerConfidence());
        if (s.recoveryLowerConfidence()) q.put("lower_confidence_reasons", List.of(DailyScorer.LOW_COVERAGE_YESTERDAY));
        q.put("drift", s.drift());
        q.put("profile_changed", profile != null);
        if (profile != null) q.put("profile", profile);
        q.put("stale", stale);

        var st = s.stored();
        return new ScoringRepository.Row(s.date(), st.recovery(), s.band(), st.strain(), st.sleepPerf(), st.sleepNeed(), loads, contributors, q);
    }
}
