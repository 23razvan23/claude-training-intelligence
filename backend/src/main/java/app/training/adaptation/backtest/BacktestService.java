package app.training.adaptation.backtest;

import app.training.adaptation.engine.Model;
import app.training.adaptation.engine.RulesEngine;
import app.training.adaptation.engine.SuggestionService;
import app.training.adaptation.settings.AppSettings;
import app.training.adaptation.settings.SettingsRepository;
import app.training.adaptation.settings.SettingsService;
import app.training.adaptation.signals.SignalRepository;
import app.training.adaptation.signals.SignalService;
import app.training.athletes.Athlete;
import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.metrics.MetricsProperties;
import app.training.metrics.MetricsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a backtest (stage 2-4, ADR 0023): reads everything once, replays every morning of the window with
 * {@link Replay}, and stores each morning's signals, suggestions and notes under a new backtest_run, in one
 * transaction (a failed run leaves nothing behind). Writes only the backtest tables.
 */
@Service
public class BacktestService {
    private final BacktestRepository repo;
    private final SignalRepository signals;
    private final SettingsService settings;
    private final SettingsRepository settingsRepo;
    private final AthleteRepository athletes;
    private final MetricsProperties metrics;
    private final MetricsRepository models;
    private final SignalService signalService;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public BacktestService(BacktestRepository repo, SignalRepository signals, SettingsService settings, SettingsRepository settingsRepo,
                           AthleteRepository athletes, MetricsProperties metrics, MetricsRepository models, SignalService signalService,
                           ObjectMapper json, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.signals = signals;
        this.settings = settings;
        this.settingsRepo = settingsRepo;
        this.athletes = athletes;
        this.metrics = metrics;
        this.models = models;
        this.signalService = signalService;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    public record Outcome(long runId, long athleteId, LocalDate from, LocalDate to, long settingsVersion, String modelVersion,
                          int mornings, int suggestions, Map<String, Integer> perRule, LocalDate firstReadinessMorning, int lateRevisionMornings) {}

    /** The default window: the last 12 months up to yesterday in the athlete's timezone. */
    public BacktestParams defaults(long athleteId) {
        return BacktestParams.lastYear(signalService.today(athleteId));
    }

    public Outcome run(long athleteId, BacktestParams p, Long userId) {
        return tx.execute(status -> runInTx(athleteId, p, userId));
    }

    /** Everything stored for the athlete; the replay filters it per morning ({@link AsOf}). */
    public AsOf.Data load(long athleteId) {
        Athlete athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        String fv = metrics.featureVersion();
        var model = models.active(athleteId).orElse(null);
        return new AsOf.Data(ZoneId.of(athlete.timezone()), signals.firstFeatureDay(athleteId, fv).orElse(null), signals.totalLoads(athleteId, fv),
                signals.running(athleteId), model == null ? Map.of() : signals.scores(athleteId, model.version()), signals.sleptMinutes(athleteId, fv),
                repo.hrvDays(athleteId, fv), repo.checkinRows(athleteId), signals.overrides(athleteId), repo.activities(athleteId),
                repo.wellnessRevisions(athleteId));
    }

    /** The settings a run uses: the version asked for, or the current one. */
    public AppSettings settingsFor(BacktestParams p) {
        if (p.settingsVersion() == null) return settings.current();
        return settingsRepo.find(p.settingsVersion())
                .orElseThrow(() -> ApiException.badRequest("Unknown settings version " + p.settingsVersion()));
    }

    /** One morning, replayed (also used by the no-look-ahead test). */
    public Replay.Morning replay(AsOf.Data data, LocalDate morning, BacktestParams p, AppSettings s) {
        return Replay.morning(data, morning, p, s, d -> tree(signals.toJson(d)));
    }

    private Outcome runInTx(long athleteId, BacktestParams p, Long userId) {
        var data = load(athleteId);
        var s = settingsFor(p);
        String model = models.active(athleteId).map(m -> m.version()).orElse(null);
        long runId = repo.insertRun(athleteId, p, RulesEngine.RULES_VERSION, SignalService.SIGNALS_VERSION, s.id(), model, userId);
        var perRule = new TreeMap<String, Integer>();
        var perAction = new TreeMap<String, Integer>();
        int mornings = 0, suggestions = 0, late = 0;
        LocalDate firstReadiness = null;
        for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1)) {
            var m = replay(data, d, p, s);
            mornings++;
            if (m.notes().stream().anyMatch(n -> n.startsWith("Garmin sent a corrected"))) late++;
            if (firstReadiness == null && m.today() != null && m.today().path("readiness").path("band").isTextual()) firstReadiness = d;
            repo.insertDay(runId, d, m.today(), m.yesterday(), json.valueToTree(m.result().healthMessages()), json.valueToTree(m.notes()));
            for (Model.Proposal pr : m.result().proposals()) {
                repo.insertSuggestion(row(runId, m, pr));
                suggestions++;
                perRule.merge(pr.rule(), 1, Integer::sum);
                perAction.merge(pr.action().name(), 1, Integer::sum);
            }
        }
        ObjectNode summary = json.createObjectNode();
        summary.put("mornings", mornings);
        summary.put("suggestions", suggestions);
        summary.set("perRule", json.valueToTree(perRule));
        summary.set("perAction", json.valueToTree(perAction));
        summary.put("firstHrvDay", data.hrvDays().isEmpty() ? null : data.hrvDays().first().toString());
        summary.put("firstReadinessMorning", firstReadiness == null ? null : firstReadiness.toString());
        summary.put("lateRevisionMornings", late);
        repo.setSummary(runId, summary);
        return new Outcome(runId, athleteId, p.from(), p.to(), s.id(), model, mornings, suggestions, new LinkedHashMap<>(perRule), firstReadiness, late);
    }

    private BacktestRepository.NewSuggestion row(long runId, Replay.Morning m, Model.Proposal p) {
        ObjectNode before = json.createObjectNode();
        before.set("session", json.valueToTree(p.session()));
        ObjectNode after = json.createObjectNode();
        after.set("session", json.valueToTree(SuggestionService.request(p.session(), p.after(), p.action() == Model.Action.REPLACE)));
        if (p.counterpart() != null) {
            before.set("counterpart", json.valueToTree(p.counterpart()));
            after.set("counterpart", json.valueToTree(SuggestionService.request(p.counterpart(), p.counterpartAfter(), false)));
        }
        ObjectNode inputs = json.createObjectNode();
        inputs.put("rule", p.rule());
        inputs.set("details", json.valueToTree(p.details()));
        inputs.set("today", json.valueToTree(m.input().todayState()));
        inputs.set("yesterday", json.valueToTree(m.input().yesterdayState()));
        inputs.set("notes", json.valueToTree(m.notes()));
        return new BacktestRepository.NewSuggestion(runId, m.morning(), p.session().date(), p.rule(), p.action().name(), p.session().id(),
                p.counterpart() == null ? null : p.counterpart().id(), before, after, json.valueToTree(p.reasons()), p.confidence().name(), inputs);
    }

    public List<BacktestRepository.Run> runs(long athleteId) {
        return repo.runs(athleteId);
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
