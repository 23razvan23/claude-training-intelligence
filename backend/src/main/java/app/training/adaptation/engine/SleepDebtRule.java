package app.training.adaptation.engine;

import static app.training.adaptation.engine.Context.details;
import static app.training.adaptation.engine.Context.label;
import static app.training.adaptation.engine.Context.num;
import static app.training.adaptation.engine.Context.reason;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Session;
import java.util.ArrayList;
import java.util.List;

/**
 * SPEC.md: "Sleep debt > 1 night's need → no VO2max work today; threshold or easy instead." Threshold when recovery
 * is green or unknown, easy when it is yellow or red (the conservative choice).
 */
final class SleepDebtRule implements Rule {
    @Override
    public String id() {
        return "sleep";
    }

    @Override
    public List<Proposal> apply(Context c) {
        var st = c.todayState();
        if (st == null || st.sleepNightsOfNeed() == null || st.sleepNightsOfNeed() <= c.s.sleepDebtNights()) return List.of();
        if ("RED".equals(st.band())) return List.of(); // the red-day rule already eases or moves today's hard session
        var out = new ArrayList<Proposal>();
        for (Session x : c.changeableOn(c.today)) {
            if (!"vo2max".equals(x.type())) continue;
            boolean easy = "YELLOW".equals(st.band()) || "RED".equals(st.band());
            int hours = st.sleepDebtMin() == null ? 0 : st.sleepDebtMin();
            var debt = reason("sleep.debt", "Sleep debt is " + hours / 60 + " h " + hours % 60 + " min over the last nights, "
                    + num(st.sleepNightsOfNeed()) + " nights of sleep need (limit " + num(c.s.sleepDebtNights()) + ").",
                    "debtMin", st.sleepDebtMin(), "nightsOfNeed", st.sleepNightsOfNeed(), "limit", c.s.sleepDebtNights());
            Change after = easy ? c.easyReplacement(x, "sleep debt and recovery " + st.band().toLowerCase(java.util.Locale.ROOT))
                    : new Change(x.date(), "threshold", x.demand(), "Threshold (instead of " + label(x) + ")",
                            "Engine: threshold work instead of VO2max today because of sleep debt; same time." + "\nPlanned: "
                                    + Context.nz(x.description()), x.distanceKm(), x.durationMin());
            var what = easy ? reason("sleep.easy", "No VO2max work today; with recovery " + st.band().toLowerCase(java.util.Locale.ROOT)
                            + " an easy run is the safer choice.", "band", st.band())
                    : reason("sleep.threshold", "No VO2max work today; threshold work instead, same time.");
            Confidence conf = st.sleepKnownNights() >= c.s.sleepMinKnownNights() ? Confidence.HIGH : Confidence.MEDIUM;
            out.add(new Proposal(id(), Action.REPLACE, x, after, null, null, List.of(debt, what), conf,
                    details("option", easy ? "easy" : "threshold", "knownNights", st.sleepKnownNights())));
        }
        return out;
    }
}
