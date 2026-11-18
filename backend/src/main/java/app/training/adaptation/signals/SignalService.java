package app.training.adaptation.signals;

import app.training.adaptation.settings.SettingsService;
import app.training.athletes.Athlete;
import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.metrics.MetricsProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * <p>When: after Recompute features and after a Garmin sync that recomputed (both call {@link #recomputeQuietly}),
 * on demand (coach endpoint, {@code signals} command). Never inside the golden check's replay (ADR 0017), which calls
 * the feature recompute directly.
 */
@Service
public class SignalService {
    /** Version of the signal code; bump it when a formula changes, so stored rows say which code made them. */
    public static final String SIGNALS_VERSION = "s1";
    private static final Logger log = LoggerFactory.getLogger(SignalService.class);

    private final SignalRepository repo;
    private final SettingsService settings;
    private final AthleteRepository athletes;
    private final MetricsProperties metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    public SignalService(SignalRepository repo, SettingsService settings, AthleteRepository athletes, MetricsProperties metrics,
                         Clock clock, PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.repo = repo;
        this.settings = settings;
        this.athletes = athletes;
        this.metrics = metrics;
        this.clock = clock;
    }

    public record Outcome(long athleteId, String signalsVersion, long settingsVersion, int days, LocalDate from, LocalDate to,
                          LoadSignals.Day latest) {}

    /** One transaction: the old rows of this version are replaced only when the new ones are all written. */
    public Outcome recompute(long athleteId) {
        return tx.execute(status -> compute(athleteId));
    }

    private Outcome compute(long athleteId) {
        Athlete athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var current = settings.current();
        var s = SignalSettings.from(current);
        LocalDate today = today(athlete);
        LocalDate first = repo.firstFeatureDay(athleteId, metrics.featureVersion()).orElse(null);
        if (first == null || first.isAfter(today)) {
            repo.replace(athleteId, SIGNALS_VERSION, current.id(), List.of());
            return new Outcome(athleteId, SIGNALS_VERSION, current.id(), 0, null, null, null);
        }
        var totals = repo.totalLoads(athleteId, metrics.featureVersion());
        var running = repo.running(athleteId);
        boolean banister = SignalSettings.BANISTER.equals(s.dailyLoad());
        var inputs = new ArrayList<LoadSignals.DayInput>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            var t = totals.get(d);
            var r = running.get(d);
            Double total = t == null ? null : banister ? t.banister() : t.edwards();
            Double runLoad = r == null ? null : banister ? r.banister() : r.edwards();
            inputs.add(new LoadSignals.DayInput(d, total, runLoad, r == null ? 0 : r.km(), r == null ? 0 : r.minutes()));
        }
        var days = LoadSignals.compute(inputs, s);
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
