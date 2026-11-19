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
 * <p>When: after Recompute features, after a Garmin sync that recomputed, after a check-in and after a tolerance
 * override (all call {@link #recomputeQuietly} or {@link #recompute} once their own change is committed), on demand (coach endpoint, {@code signals} command). Never inside the golden check's replay (ADR 0017), which calls
 * the feature recompute directly.
 */
@Service
public class SignalService {
    /** Version of the signal code; bump it when a formula changes, so stored rows say which code made them. */
    public static final String SIGNALS_VERSION = "s2"; // s1: load signals only (PR 1); s2: + readiness, sleep, check-ins, sessions, tolerance
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
        var grid = new ArrayList<LocalDate>();
        var inputs = new ArrayList<LoadSignals.DayInput>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            grid.add(d);
            var t = totals.get(d);
            var r = running.get(d);
            Double total = t == null ? null : banister ? t.banister() : t.edwards();
            Double runLoad = r == null ? null : banister ? r.banister() : r.edwards();
            inputs.add(new LoadSignals.DayInput(d, total, runLoad, r == null ? 0 : r.km(), r == null ? 0 : r.minutes()));
        }
        var load = LoadSignals.compute(inputs, s);

        // Readiness and sleep need come from the ACTIVE model's scores; without one there are none (said by nulls).
        var model = models.active(athleteId).orElse(null);
        var scores = model == null ? java.util.Map.<LocalDate, SignalRepository.Score>of() : repo.scores(athleteId, model.version());
        var slept = repo.sleptMinutes(athleteId, metrics.featureVersion());
        LocalDate firstHrv = repo.firstHrvDay(athleteId, metrics.featureVersion()).orElse(null);
        var readinessIn = new ArrayList<Readiness.Input>();
        var sleepIn = new ArrayList<SleepDebt.Input>();
        for (LocalDate d : grid) {
            var sc = scores.get(d);
            readinessIn.add(new Readiness.Input(d, sc == null ? null : sc.recoveryPct(), sc == null ? null : sc.band()));
            sleepIn.add(new SleepDebt.Input(d, sc == null ? null : sc.sleepNeedMin(), slept.get(d)));
        }
        var readiness = Readiness.compute(readinessIn, current.intAt("signals.readiness.trendShortDays"),
                current.intAt("signals.readiness.trendLongDays"), firstHrv);
        var sleep = SleepDebt.compute(sleepIn, current.intAt("signals.sleep.debtWindowDays"), current.boolAt("signals.sleep.surplusPaysBack"));
        var subjective = Subjective.compute(grid, repo.checkins(athleteId), current.intAt("signals.subjective.niggleStaleDays"));
        var sessions = repo.sessions(athleteId, first, today);

        var tolIn = new ArrayList<Tolerance.Input>();
        for (int i = 0; i < grid.size(); i++) {
            tolIn.add(new Tolerance.Input(grid.get(i), inputs.get(i).runKm(), inputs.get(i).totalLoad() != null, subjective.get(i).flagged()));
        }
        var tolerance = Tolerance.compute(tolIn, Tolerance.Settings.from(current), overridesPerDay(athlete, grid));

        var days = new ArrayList<DaySignals>(grid.size());
        for (int i = 0; i < grid.size(); i++) {
            days.add(new DaySignals(grid.get(i), load.get(i), readiness.get(i), sleep.get(i), subjective.get(i),
                    DaySignals.Sessions.of(sessions.getOrDefault(grid.get(i), List.of())), tolerance.get(i)));
        }
        repo.replace(athleteId, SIGNALS_VERSION, current.id(), days);
        return new Outcome(athleteId, SIGNALS_VERSION, current.id(), days.size(), first, today, days.get(days.size() - 1));
    }

    /** The override in force on each day: the newest one set on or before that day (athlete's timezone). */
    private List<Tolerance.OverrideValue> overridesPerDay(Athlete athlete, List<LocalDate> grid) {
        ZoneId zone = ZoneId.of(athlete.timezone());
        var rows = repo.overrides(athlete.id());
        var out = new ArrayList<Tolerance.OverrideValue>(grid.size());
        int k = -1;
        for (LocalDate d : grid) {
            while (k + 1 < rows.size() && !rows.get(k + 1).createdAt().atZone(zone).toLocalDate().isAfter(d)) k++;
            if (k < 0 || rows.get(k).kmPerWeek() == null) out.add(null);
            else out.add(new Tolerance.OverrideValue(rows.get(k).kmPerWeek(), rows.get(k).note(),
                    rows.get(k).createdAt().atZone(zone).toLocalDate()));
        }
        return out;
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
