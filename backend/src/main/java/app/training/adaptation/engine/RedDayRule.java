package app.training.adaptation.engine;

import static app.training.adaptation.engine.Context.day;
import static app.training.adaptation.engine.Context.details;
import static app.training.adaptation.engine.Context.label;
import static app.training.adaptation.engine.Context.reason;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Reason;
import app.training.adaptation.engine.Model.Session;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * SPEC.md: "Recovery red + hard session today → swap with the next easy day, or turn it into easy/rest; keep the key
 * session within the week if possible." (ADR 0022 Q7.)
 * <ol>
 *   <li>Swap with an easy or rest session at most {@code swapWindowDays} later, in the same Monday-Sunday week first.</li>
 *   <li>A key session with no easy day to swap with moves to a free day of the same week, then of the swap window
 *       (guardrail: protect key sessions by moving them).</li>
 *   <li>Otherwise it becomes an easy session. In race week or taper only that reduction is offered (guardrail).</li>
 * </ol>
 */
final class RedDayRule implements Rule {
    @Override
    public String id() {
        return "red";
    }

    @Override
    public List<Proposal> apply(Context c) {
        var st = c.todayState();
        if (st == null || !"RED".equals(st.band())) return List.of();
        var out = new ArrayList<Proposal>();
        Reason red = reason("red.recovery", "Recovery is red today (" + st.recoveryPct() + "%).", "recoveryPct", st.recoveryPct());
        for (Session hard : c.changeableOn(c.today)) {
            if (!hard.hard()) continue;
            out.add(propose(c, hard, red));
        }
        return out;
    }

    private Proposal propose(Context c, Session hard, Reason red) {
        if (c.protectedSession(hard)) {
            return new Proposal(id(), Action.REPLACE, hard, c.easyReplacement(hard, "recovery is red"), null, null,
                    List.of(red, reason("guard.raceWeek", "It is race week or taper, so only a reduction is suggested, not a move.")),
                    Confidence.HIGH, details("option", "easy"));
        }
        LocalDate windowEnd = c.today.plusDays(c.s.swapWindowDays());
        Optional<Session> sameWeek = swapPartner(c, hard, windowEnd, true);
        if (sameWeek.isPresent()) return swap(c, hard, sameWeek.get(), red);
        LocalDate sunday = Model.monday(c.today).plusDays(6);
        if (hard.key()) {
            var move = c.freeDay(hard, c.today.plusDays(1), sunday);
            if (move.isPresent()) return move(c, hard, move.get(), red, true);
        }
        Optional<Session> later = swapPartner(c, hard, windowEnd, false);
        if (later.isPresent()) return swap(c, hard, later.get(), red);
        if (hard.key()) {
            var move = c.freeDay(hard, sunday.plusDays(1), windowEnd);
            if (move.isPresent()) return move(c, hard, move.get(), red, false);
        }
        var reasons = new ArrayList<Reason>(List.of(red));
        reasons.add(hard.key()
                ? reason("red.noRoom", "No easy or free day in the next " + c.s.swapWindowDays() + " days to move this key session to, so it "
                        + "becomes an easy run; consider placing it again later.", "windowDays", c.s.swapWindowDays())
                : reason("red.easy", "No easy day in the next " + c.s.swapWindowDays() + " days to swap with, so it becomes an easy run.",
                        "windowDays", c.s.swapWindowDays()));
        return new Proposal(id(), Action.REPLACE, hard, c.easyReplacement(hard, "recovery is red"), null, null, reasons,
                hard.key() ? Confidence.MEDIUM : Confidence.HIGH, details("option", "easy"));
    }

    /** The first easy or rest session after today within the window (same week only, or any week), on a day with nothing hard. */
    private Optional<Session> swapPartner(Context c, Session hard, LocalDate windowEnd, boolean sameWeekOnly) {
        return c.changeable().stream()
                .filter(x -> x.date().isAfter(c.today) && !x.date().isAfter(windowEnd))
                .filter(x -> !x.hard() && !c.protectedSession(x))
                .filter(x -> !sameWeekOnly || Model.sameWeek(x.date(), c.today))
                .filter(x -> c.on(x.date()).stream().noneMatch(o -> o.id() != x.id() && o.hard()))
                .min(Comparator.comparing(Session::date).thenComparing(Session::id));
    }

    private Proposal swap(Context c, Session hard, Session easy, Reason red) {
        boolean sameWeek = Model.sameWeek(easy.date(), c.today);
        String where = Context.capital(label(hard)) + " swaps with " + day(easy.date()) + "'s " + label(easy)
                + (sameWeek ? ", so it stays in this week." : "; there was no easy day left this week.");
        return new Proposal(id(), Action.SWAP, hard, Change.of(hard).withDate(easy.date()), easy, Change.of(easy).withDate(c.today),
                List.of(red, reason("red.swap", where, "swapDate", easy.date().toString(), "sameWeek", sameWeek)),
                Confidence.HIGH, details("option", "swap"));
    }

    private Proposal move(Context c, Session key, LocalDate to, Reason red, boolean sameWeek) {
        String where = "No easy day to swap with, so this key session moves to " + day(to)
                + (sameWeek ? ", a free day this week." : ", the first free day.");
        return new Proposal(id(), Action.MOVE, key, Change.of(key).withDate(to), null, null,
                List.of(red, reason("red.move", where, "moveDate", to.toString(), "sameWeek", sameWeek)), Confidence.HIGH,
                details("option", "move"));
    }
}
