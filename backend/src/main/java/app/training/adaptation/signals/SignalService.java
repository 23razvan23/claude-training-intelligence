package app.training.adaptation.signals;

import app.training.adaptation.settings.SettingsService;
import app.training.athletes.Athlete;
import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.metrics.MetricsProperties;
import app.training.metrics.MetricsRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Computes and stores the adaptive engine's signals per athlete per day (stage 2-2, ADR 0021) in day_signals, tagged
 * with {@link #SIGNALS_VERSION} and the settings version used. Recomputes the whole history each time (a few hundred
 * days, milliseconds of arithmetic), from the first day with features to today in the athlete's timezone.
 *
 * <p>When: after Recompute features, after a Garmin sync that recomputed, after a check-in and after a tolerance
 * override (all call {@link #recomputeQuietly} or {@link #recompute} once their own change is committed), on demand (coach endpoint, {@code signals} command). Never inside the golden check's replay (ADR 0017), which calls
 * the feature recompute directly.
 */
@Service
public class SignalService {
    /** Version of the signal code; bump it when a formula changes, so stored rows say which code made them. */
    // s1: load signals only (PR 1); s2: + readiness, sleep, check-ins, sessions, tolerance; s3: tolerance decays only for the
    // current break (ADR 0021 amendment, 2026-11-19).
    public static final String SIGNALS_VERSION = "s3";
    private static final Logger log = LoggerFactory.getLogger(SignalService.class);

    private final SignalRepository repo;
    private final SettingsService settings;
    private final AthleteRepository athletes;
    private final MetricsProperties metrics;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final MetricsRepository models;

    public SignalService(SignalRepository repo, SettingsService settings, AthleteRepository athletes, MetricsProperties metrics,
                         Clock clock, PlatformTransactionManager txManager, MetricsRepository models) {
        this.models = models;
        this.tx = new TransactionTemplate(txManager);
        // Its own transaction even when called from inside another one: a failure here never rolls back the caller.
        this.tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repo = repo;
        this.settings = settings;
        this.athletes = athletes;
        this.metrics = metrics;
        this.clock = clock;
    }

    public record Outcome(long athleteId, String signalsVersion, long settingsVersion, int days, LocalDate from, LocalDate to,
                          DaySignals latest) {}

    /** One transaction: the old rows of this version are replaced only when the new ones are all written. */
    public Outcome recompute(long athleteId) {
        return tx.execute(status -> compute(athleteId));
    }

    private Outcome compute(long athleteId) {
        Athlete athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var current = settings.current();
        LocalDate today = today(athlete);
        LocalDate first = repo.firstFeatureDay(athleteId, metrics.featureVersion()).orElse(null);
        if (first == null || first.isAfter(today)) {
            repo.replace(athleteId, SIGNALS_VERSION, current.id(), List.of());
            return new Outcome(athleteId, SIGNALS_VERSION, current.id(), 0, null, null, null);
        }
        // Readiness and sleep need come from the ACTIVE model's scores; without one there are none (said by nulls).
        var model = models.active(athleteId).orElse(null);
        var scores = model == null ? java.util.Map.<LocalDate, SignalRepository.Score>of() : repo.scores(athleteId, model.version());
        var in = new SignalCalculator.Inputs(first, today, repo.totalLoads(athleteId, metrics.featureVersion()), repo.running(athleteId),
                scores, repo.sleptMinutes(athleteId, metrics.featureVersion()),
                repo.firstHrvDay(athleteId, metrics.featureVersion()).orElse(null), repo.checkins(athleteId),
                repo.sessions(athleteId, first, today), repo.overrides(athleteId), ZoneId.of(athlete.timezone()));
        var days = SignalCalculator.compute(in, current);
        repo.replace(athleteId, SIGNALS_VERSION, current.id(), days);
        return new Outcome(athleteId, SIGNALS_VERSION, current.id(), days.size(), first, today, days.get(days.size() - 1));
    }

    /** For the feature recompute and the sync: a failure here is logged and never undoes or fails the scores. */
    public void recomputeQuietly(long athleteId) {
        try {
            var o = recompute(athleteId);
            log.info("Signals {} (settings version {}) for athlete {}: {} days", o.signalsVersion(), o.settingsVersion(), athleteId, o.days());
        } catch (RuntimeException e) {
            log.warn("Signals for athlete {} failed: {}", athleteId, e.getClass().getSimpleName());
        }
    }

    /** Stored signals of the current settings version, oldest first. */
    public List<SignalRepository.Stored> read(long athleteId, LocalDate from, LocalDate to) {
        if (athletes.findById(athleteId).isEmpty()) throw ApiException.notFound("Athlete");
        return repo.read(athleteId, SIGNALS_VERSION, settings.current().id(), from, to);
    }

    public LocalDate today(Athlete athlete) {
        return LocalDate.now(clock.withZone(ZoneId.of(athlete.timezone())));
    }

    public LocalDate today(long athleteId) {
        return today(athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete")));
    }
}
