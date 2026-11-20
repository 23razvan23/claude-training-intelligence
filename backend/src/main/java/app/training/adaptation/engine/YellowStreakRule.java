package app.training.adaptation.engine;

import static app.training.adaptation.engine.Context.details;
import static app.training.adaptation.engine.Context.pct;
import static app.training.adaptation.engine.Context.reason;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Session;
import java.util.ArrayList;
import java.util.List;

/**
 * SPEC.md: "Recovery yellow 2+ days in a row + hard session → reduce reps or time at intensity by 20-30%, keep the
 * warm-up and the session's purpose." Today must be yellow; the streak counts consecutive non-green scored days, so a
 * red day before a yellow one counts too (the cautious reading, said in the reason). Distance and time are cut by
 * {@code reduce.share} and the note says how (ADR 0022 Q4: the workout is free text, so the engine cannot rewrite it).
 */
final class YellowStreakRule implements Rule {
    @Override
    public String id() {
        return "yellow";
    }

    @Override
    public List<Proposal> apply(Context c) {
        var st = c.todayState();
        if (st == null || !"YELLOW".equals(st.band()) || st.nonGreenStreakDays() < c.s.yellowStreakDays()) return List.of();
        var out = new ArrayList<Proposal>();
        for (Session hard : c.changeableOn(c.today)) {
            if (!hard.hard()) continue;
            String share = pct(c.s.reduceShare());
            out.add(new Proposal(id(), Action.REDUCE, hard,
                    c.reduced(hard, c.s.reduceShare(), "cut reps or time at intensity by " + share + "; keep the warm-up and the session's purpose."),
                    null, null,
                    List.of(reason("yellow.streak", "Recovery has not been green for " + st.nonGreenStreakDays() + " days in a row (yellow today, "
                                    + st.recoveryPct() + "%).", "days", st.nonGreenStreakDays(), "recoveryPct", st.recoveryPct()),
                            reason("yellow.reduce", "Reduce the work at intensity by " + share + ", keeping the warm-up and the purpose.",
                                    "share", c.s.reduceShare())),
                    Confidence.MEDIUM, details("share", c.s.reduceShare())));
        }
        return out;
    }
}
