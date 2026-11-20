package app.training.adaptation.backtest;

import app.training.adaptation.engine.EngineSettings;
import app.training.adaptation.engine.Model;
import app.training.adaptation.engine.RulesEngine;
import app.training.adaptation.engine.SuggestionService;
import app.training.adaptation.settings.AppSettings;
import app.training.adaptation.settings.SettingsCatalog;
import app.training.adaptation.signals.DaySignals;
import app.training.adaptation.signals.SignalCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Replays one past morning with the live code (ADR 0023 D1): the inputs that existed that morning ({@link AsOf}) go
 * through {@link SignalCalculator} (the same arithmetic as the live signals) and are turned into JSON exactly as
 * day_signals stores it; then {@link SuggestionService#input} (the same assembly as the live run) and
 * {@link RulesEngine#evaluate} (the same rules) decide. Nothing here is a copy of a rule. Pure apart from the
 * {@code toJson} function it is given.
 *
 * <p>Each morning stands alone (Q2): no suggestion is taken as accepted, so the plan stays what was actually done,
 * nothing counts as decided, and no restore step counts as recently accepted.
 */
public final class Replay {
    private Replay() {}

    /** One replayed morning: the signals the rules read (JSON as stored), the engine's input and its result. */
    public record Morning(LocalDate morning, JsonNode today, JsonNode yesterday, Model.Input input, Model.Result result, List<String> notes) {}

    public static Morning morning(AsOf.Data data, LocalDate morning, BacktestParams p, AppSettings settings,
                                  Function<DaySignals, JsonNode> toJson) {
        Function<String, String> demand = demandFor(settings);
        // The plan the rules read (live: 28 days back to 14 ahead) and the session demand the signals store (from the first day).
        var plan = ActivityPlan.sessions(data.activities(), morning, morning.minusDays(SuggestionService.PAST_DAYS),
                morning.plusDays(SuggestionService.AHEAD_DAYS), p, demand);
        var history = data.firstFeatureDay() == null ? List.<Model.Session>of()
                : ActivityPlan.sessions(data.activities(), morning, data.firstFeatureDay(), morning, p, demand);
        var days = SignalCalculator.compute(AsOf.inputs(data, morning, p, ActivityPlan.perDay(history)), settings);

        var states = new HashMap<LocalDate, JsonNode>();
        for (int i = Math.max(0, days.size() - 9); i < days.size(); i++) states.put(days.get(i).date(), toJson.apply(days.get(i)));
        var input = SuggestionService.input(morning, states, AsOf.runKm(data, morning), plan, Set.of(), false);
        var result = RulesEngine.evaluate(input, EngineSettings.from(settings));
        var notes = new ArrayList<>(result.notes());
        notes.addAll(AsOf.lateRevisions(data, morning, p));
        return new Morning(morning, states.get(morning), states.get(morning.minusDays(1)), input, result, List.copyOf(notes));
    }

    /** The demand each session type starts with in this settings version (planning.defaultDemand.*); EASY if unknown. */
    static Function<String, String> demandFor(AppSettings settings) {
        return type -> {
            var n = SettingsCatalog.at(settings.settings(), "planning.defaultDemand." + type);
            return n != null && n.isTextual() ? n.asText() : "EASY";
        };
    }
}
