package app.training.adaptation.engine;

import static app.training.adaptation.engine.Context.day;
import static app.training.adaptation.engine.Context.details;
import static app.training.adaptation.engine.Context.label;
import static app.training.adaptation.engine.Context.reason;
import static app.training.adaptation.engine.Context.round1;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Niggle;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Reason;
import app.training.adaptation.engine.Model.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SPEC.md: "Niggle flagged → remove intensity and downhill running for that area; offer cross-training; health
 * message if it persists 3+ days." While a niggle episode is open today (ADR 0021 D11), every session at intensity in
 * the window becomes an easy run on flat ground of the same time, with cross-training offered instead. Sessions are not
 * moved for a niggle: a later day does not help it. The health message is given by {@link RulesEngine#healthMessages}.
 */
final class NiggleRule implements Rule {
    @Override
    public String id() {
        return "niggle";
    }

    @Override
    public List<Proposal> apply(Context c) {
        var st = c.todayState();
        if (st == null || st.niggles() == null || st.niggles().isEmpty()) return List.of();
        String areas = st.niggles().stream().map(Niggle::location).collect(Collectors.joining(", "));
        Niggle first = st.niggles().get(0);
        Reason why = reason("niggle.open", "A niggle (" + areas + ") is open since " + day(first.since()) + ", " + first.persistDays()
                + (first.persistDays() == 1 ? " day." : " days."), "areas", areas, "since", first.since().toString(),
                "persistDays", first.persistDays());
        var out = new ArrayList<Proposal>();
        for (Session x : c.changeable()) {
            if (!x.running() || !x.intensity()) continue;
            Change after = new Change(x.date(), "easy", "EASY", "Easy run, flat (instead of " + label(x) + ")",
                    "Engine: easy run on flat ground, no intensity and no downhill, because of the niggle (" + areas
                            + "). Cross-training of the same time is fine instead if running hurts." + (x.description() == null ? ""
                            : "\nPlanned: " + x.description()), x.distanceKm() == null ? null : round1(x.distanceKm()), x.durationMin());
            out.add(new Proposal(id(), Action.REPLACE, x, after, null, null,
                    List.of(why, reason("niggle.easy", "No intensity or downhill running while the niggle is open; cross-training is an option.")),
                    Confidence.HIGH, details("areas", areas)));
        }
        return out;
    }
}
