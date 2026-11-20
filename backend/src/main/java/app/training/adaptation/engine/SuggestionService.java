package app.training.adaptation.engine;

import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.DayState;
import app.training.adaptation.engine.Model.Niggle;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Reason;
import app.training.adaptation.engine.SuggestionRepository.NewSuggestion;
import app.training.adaptation.engine.SuggestionRepository.Suggestion;
import app.training.adaptation.settings.SettingsService;
import app.training.adaptation.signals.SignalRepository;
import app.training.adaptation.signals.SignalService;
import app.training.metrics.MetricsRepository;
import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.PlanService;
import app.training.planning.SessionTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the rules engine for one athlete and stores its suggestions (stage 2-3, ADR 0022; ADR 0018 D3-D4).
 * <ul>
 *   <li>Reads today's and the recent days' signals (day_signals, current settings version), the plan from 3 weeks back
 *       to 2 weeks ahead, the km run, and which sessions were already decided on today.</li>
 *   <li>Stores each proposal as a PENDING adaptation_suggestion with before/after, reasons, confidence, the inputs
 *       snapshot and every version. A re-run keeps an identical pending suggestion, expires one whose inputs or
 *       content changed and stores the fresh one, and expires every pending suggestion it no longer makes
 *       (including those about past days). The database refuses a second PENDING copy ({@code dedup_key}).</li>
 * </ul>
 * When: after the morning Garmin sync and after a check-in ({@link #runQuietly}), on demand (coach button,
 * {@code suggest} command). Never inside the golden check's replay.
 */
@Service
public class SuggestionService {
    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    /** How far back the plan and the km run are read: the restore rule looks at the last full weeks. */
    static final int PAST_DAYS = 28;
    /** How far ahead the plan is read: race week can start after the 7 reviewed days. */
    static final int AHEAD_DAYS = 14;

    private final SuggestionRepository repo;
    private final SignalService signals;
    private final SignalRepository signalRepo;
    private final SettingsService settings;
    private final MetricsRepository models;
    private final PlanService plans;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public SuggestionService(SuggestionRepository repo, SignalService signals, SignalRepository signalRepo, SettingsService settings,
                             MetricsRepository models, PlanService plans, ObjectMapper json, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.signals = signals;
        this.signalRepo = signalRepo;
        this.settings = settings;
        this.models = models;
        this.plans = plans;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        // Its own transaction: a failure here never rolls back the sync or check-in that called it.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record RunOutcome(long athleteId, LocalDate engineRunDate, String rulesVersion, long settingsVersion, String signalsVersion,
                             int created, int kept, int expired, List<Suggestion> pending, List<Reason> healthMessages, List<String> notes) {}

    /** On demand: recompute the signals first (so a new check-in or plan edit counts), then run the engine. */
    public RunOutcome runWithFreshSignals(long athleteId) {
        signals.recompute(athleteId);
        return run(athleteId);
    }

    public RunOutcome run(long athleteId) {
        return tx.execute(status -> runInTx(athleteId));
    }

    /** After the sync and a check-in: a failure is logged and never fails the caller. */
    public void runQuietly(long athleteId) {
        try {
            var o = run(athleteId);
            log.info("Rules {} for athlete {} on {}: {} new, {} kept, {} expired suggestion(s)", o.rulesVersion(), athleteId,
                    o.engineRunDate(), o.created(), o.kept(), o.expired());
        } catch (RuntimeException e) {
            log.warn("Rules engine for athlete {} failed: {}", athleteId, e.getClass().getSimpleName());
        }
    }

    public List<Suggestion> list(long athleteId, String status, LocalDate from, LocalDate to) {
        return repo.list(athleteId, status, from, to);
    }

    private RunOutcome runInTx(long athleteId) {
        LocalDate today = signals.today(athleteId); // 404 for an unknown athlete
        repo.lock(athleteId);
        var current = settings.current();
        var s = EngineSettings.from(current);
        String signalsVersion = SignalService.SIGNALS_VERSION;

        var states = new HashMap<LocalDate, JsonNode>();
        for (var row : signalRepo.read(athleteId, signalsVersion, current.id(), today.minusDays(8), today)) states.put(row.date(), row.signals());
        var illness = new HashSet<LocalDate>();
        states.forEach((d, n) -> {
            if (n.path("subjective").path("illness").asBoolean(false)) illness.add(d);
        });
        var runKm = new HashMap<LocalDate, Double>();
        signalRepo.running(athleteId).forEach((d, r) -> {
            if (!d.isBefore(today.minusDays(PAST_DAYS)) && !d.isAfter(today)) runKm.put(d, r.km());
        });
        var input = new Model.Input(today, state(today, states.get(today)), state(today.minusDays(1), states.get(today.minusDays(1))),
                illness, runKm, repo.sessions(athleteId, today.minusDays(PAST_DAYS), today.plusDays(AHEAD_DAYS)),
                repo.decidedSessions(athleteId, today), repo.restoreAcceptedSince(athleteId, today.minusDays(6)));
        var result = RulesEngine.evaluate(input, s);
        String model = models.active(athleteId).map(m -> m.version()).orElse(null);

        var pending = new ArrayList<>(repo.pending(athleteId));
        int created = 0, kept = 0, expired = 0;
        var keep = new HashSet<Long>();
        for (Proposal p : result.proposals()) {
            var row = toRow(athleteId, p, input, current.id(), signalsVersion, model, today, result.notes());
            Suggestion same = null;
            for (Suggestion old : pending) {
                if (sameSuggestion(old, row)) same = old;
            }
            if (same != null && sameContent(same, row)) {
                keep.add(same.id());
                kept++;
                continue;
            }
            if (same != null) {
                repo.expire(same.id());
                pending.remove(same);
                expired++;
            }
            repo.insert(row);
            created++;
        }
        for (Suggestion old : pending) {
            if (keep.contains(old.id())) continue;
            repo.expire(old.id());
            expired++;
        }
        return new RunOutcome(athleteId, today, RulesEngine.RULES_VERSION, current.id(), signalsVersion, created, kept, expired,
                repo.pending(athleteId), result.healthMessages(), result.notes());
    }

    /** The six values of dedup_key (ADR 0018 D3). */
    private static boolean sameSuggestion(Suggestion old, NewSuggestion n) {
        return Objects.equals(old.plannedSessionId(), n.sessionId()) && Objects.equals(old.counterpartSessionId(), n.counterpartId())
                && old.forDate().equals(n.forDate()) && old.action().equals(n.action()) && old.rulesVersion().equals(n.rulesVersion())
                && Objects.equals(old.settingsVersion(), n.settingsVersion());
    }

    private static boolean sameContent(Suggestion old, NewSuggestion n) {
        return Objects.equals(old.before(), n.before()) && Objects.equals(old.after(), n.after()) && Objects.equals(old.reasons(), n.reasons())
                && Objects.equals(old.confidence(), n.confidence()) && Objects.equals(old.inputsSnapshot(), n.inputs())
                && Objects.equals(old.signalsVersion(), n.signalsVersion()) && Objects.equals(old.modelVersion(), n.modelVersion());
    }

    private NewSuggestion toRow(long athleteId, Proposal p, Model.Input in, long settingsVersion, String signalsVersion, String model,
                                LocalDate today, List<String> notes) {
        ObjectNode before = json.createObjectNode();
        before.set("session", json.valueToTree(plans.session(p.session().id())));
        ObjectNode after = json.createObjectNode();
        after.set("session", json.valueToTree(request(p.session(), p.after(), p.action() == Model.Action.REPLACE)));
        if (p.counterpart() != null) {
            before.set("counterpart", json.valueToTree(plans.session(p.counterpart().id())));
            after.set("counterpart", json.valueToTree(request(p.counterpart(), p.counterpartAfter(), false)));
        }
        ObjectNode inputs = json.createObjectNode();
        inputs.put("rule", p.rule());
        inputs.set("details", json.valueToTree(p.details()));
        inputs.set("today", json.valueToTree(in.todayState()));
        inputs.set("yesterday", json.valueToTree(in.yesterdayState()));
        inputs.set("notes", json.valueToTree(notes));
        return new NewSuggestion(athleteId, p.session().id(), p.counterpart() == null ? null : p.counterpart().id(), p.session().date(),
                p.action().name(), plain(before), plain(after), plain(json.valueToTree(p.reasons())), plain(inputs), p.confidence().name(),
                RulesEngine.RULES_VERSION, model, settingsVersion, signalsVersion, today);
    }

    /** As the database gives it back (decimals as plain numbers), so a re-run can compare old and new content. */
    private JsonNode plain(JsonNode n) {
        try {
            return json.readTree(json.writeValueAsString(n));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What a session becomes, in the shape the plan API takes ({@link SessionRequest}), so accepting is one call to
     * {@link PlanService}. A replacement is a new session: no estimated strain, no notes carried over. Otherwise the
     * estimated strain and notes stay (strain is a log scale, so it is not scaled with the distance).
     */
    static SessionRequest request(Model.Session s, Change c, boolean replacement) {
        return new SessionRequest(s.planId(), c.date(), c.type(), SessionTypes.Demand.valueOf(c.demand()), c.title(), c.description(),
                c.distanceKm() == null ? null : BigDecimal.valueOf(c.distanceKm()), c.durationMin(),
                replacement || s.estStrain() == null ? null : BigDecimal.valueOf(s.estStrain()), SessionTypes.Status.PLANNED,
                replacement ? null : s.notes(), s.taper());
    }

    /** Today's or yesterday's stored signals as the rules read them; null when that day has none. */
    static DayState state(LocalDate date, JsonNode n) {
        if (n == null) return null;
        var r = n.path("readiness");
        var sl = n.path("sleep");
        var sub = n.path("subjective");
        var tol = n.path("tolerance");
        var niggles = new ArrayList<Niggle>();
        for (JsonNode x : sub.path("niggles")) {
            niggles.add(new Niggle(x.path("location").asText(), LocalDate.parse(x.path("since").asText()), x.path("persistDays").asInt()));
        }
        return new DayState(date, intOrNull(r.path("recoveryPct")), textOrNull(r.path("band")), r.path("nonGreenStreakDays").asInt(0),
                r.path("beforeHrv").asBoolean(false), intOrNull(sl.path("debtMin")), doubleOrNull(sl.path("nightsOfNeed")),
                sl.path("knownNights").asInt(0), sub.path("illness").asBoolean(false), List.copyOf(niggles),
                doubleOrNull(n.path("load").path("running").path("acwr")), doubleOrNull(tol.path("kmPerWeek")), textOrNull(tol.path("source")));
    }

    private static Integer intOrNull(JsonNode n) {
        return n.isNumber() ? n.asInt() : null;
    }

    private static Double doubleOrNull(JsonNode n) {
        return n.isNumber() ? n.asDouble() : null;
    }

    private static String textOrNull(JsonNode n) {
        return n.isTextual() ? n.asText() : null;
    }

    /** What Today shows: the pending suggestions of the next days and the health messages (stage 2-3 PR 2). */
    public record TodayView(LocalDate date, List<Suggestion> pending, List<Reason> healthMessages) {}

    public TodayView todayView(long athleteId) {
        LocalDate today = signals.today(athleteId);
        var current = settings.current();
        var s = EngineSettings.from(current);
        DayState st = null;
        for (var row : signalRepo.read(athleteId, SignalService.SIGNALS_VERSION, current.id(), today, today)) st = state(today, row.signals());
        return new TodayView(today, repo.list(athleteId, "PENDING", today, RulesEngine.lastDay(today, s)), RulesEngine.healthMessages(st, s));
    }

    /** Every suggestion about one session, newest first (Plan: suggestion history per session). */
    public List<Suggestion> forSession(long sessionId) {
        return repo.forSession(sessionId);
    }

    /** For the API: today in the athlete's timezone; 404 for an unknown athlete. */
    public LocalDate today(long athleteId) {
        return signals.today(athleteId);
    }
}
