package app.training.adaptation.engine;

import static app.training.adaptation.engine.Context.day;
import static app.training.adaptation.engine.Context.details;
import static app.training.adaptation.engine.Context.pct;
import static app.training.adaptation.engine.Context.reason;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Reason;
import app.training.adaptation.engine.Model.Session;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * SPEC.md: "Illness flagged → rest or very easy until symptoms clear, then a graded return over 2-3 days." (ADR 0022 Q8.)
 * <ul>
 *   <li>Illness is current when it was reported within the last {@code illness.activeDays} days (today or yesterday):
 *       every session today becomes rest; a key session first tries to move past the return days, within the week
 *       (guardrail: protect key sessions by moving them).</li>
 *   <li>Return day 1: a hard session becomes easy (or a key session moves). Return days 2 … {@code returnDays}: a hard
 *       session is reduced by {@code reduce.share}.</li>
 * </ul>
 */
final class IllnessRule implements Rule {
    @Override
    public String id() {
        return "illness";
    }

    @Override
    public List<Proposal> apply(Context c) {
        LocalDate last = c.in.illnessDays().stream().filter(d -> !d.isAfter(c.today)).max(LocalDate::compareTo).orElse(null);
        if (last == null) return List.of();
        int since = (int) ChronoUnit.DAYS.between(last, c.today);
        int returnDay = since - c.s.illnessActiveDays() + 1; // 1 = first day after illness stopped being current
        if (returnDay > c.s.illnessReturnDays()) return List.of();
        LocalDate normalFrom = last.plusDays(c.s.illnessActiveDays() + c.s.illnessReturnDays());
        var out = new ArrayList<Proposal>();
        for (Session x : c.changeableOn(c.today)) {
            if (returnDay <= 0) {
                if ("rest".equals(x.type())) continue;
                Reason ill = reason("illness.current", "Illness was reported " + (since == 0 ? "today" : since == 1 ? "yesterday" : day(last))
                        + ": rest or very easy until symptoms clear.", "lastIllness", last.toString());
                Proposal move = x.key() ? moveKey(c, x, normalFrom, ill) : null;
                out.add(move != null ? move : new Proposal(id(), Action.REPLACE, x, c.restReplacement(x, "illness"), null, null,
                        List.of(ill), Confidence.HIGH, details("phase", "ill")));
                continue;
            }
            if (!x.hard()) continue;
            Reason back = reason("illness.return", "Day " + returnDay + " of a " + c.s.illnessReturnDays()
                    + "-day graded return after illness (last reported " + day(last) + ").", "returnDay", returnDay, "lastIllness", last.toString());
            if (returnDay == 1) {
                Proposal move = x.key() ? moveKey(c, x, normalFrom, back) : null;
                out.add(move != null ? move : new Proposal(id(), Action.REPLACE, x, c.easyReplacement(x, "first day back after illness"),
                        null, null, List.of(back, reason("illness.easy", "Easy today; harder work comes back gradually.")), Confidence.HIGH,
                        details("phase", "return", "returnDay", returnDay)));
            } else {
                String share = pct(c.s.reduceShare());
                out.add(new Proposal(id(), Action.REDUCE, x,
                        c.reduced(x, c.s.reduceShare(), "graded return after illness: cut reps or time at intensity by " + share + "."), null, null,
                        List.of(back, reason("illness.reduce", "Reduce the work at intensity by " + share + " today.", "share", c.s.reduceShare())),
                        Confidence.HIGH, details("phase", "return", "returnDay", returnDay)));
            }
        }
        return out;
    }

    private Proposal moveKey(Context c, Session key, LocalDate normalFrom, Reason why) {
        if (c.protectedSession(key)) return null;
        LocalDate sunday = Model.monday(c.today).plusDays(6);
        var to = c.freeDay(key, normalFrom, sunday);
        if (to.isEmpty()) return null;
        return new Proposal(id(), Action.MOVE, key, Change.of(key).withDate(to.get()), null, null,
                List.of(why, reason("illness.move", "This key session moves to " + day(to.get()) + ", after the graded return, in the same week.",
                        "moveDate", to.get().toString())), Confidence.HIGH, details("option", "move"));
    }
}
