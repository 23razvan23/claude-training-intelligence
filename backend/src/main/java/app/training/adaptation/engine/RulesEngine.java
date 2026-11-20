package app.training.adaptation.engine;

import static app.training.adaptation.engine.Context.day;
import static app.training.adaptation.engine.Context.label;
import static app.training.adaptation.engine.Context.reason;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.Input;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Reason;
import app.training.adaptation.engine.Model.Result;
import app.training.adaptation.engine.Model.Session;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic rules engine (stage 2-3, SPEC.md Module 3, ADR 0022). Pure: the same input and settings give the
 * same proposals. Steps:
 * <ol>
 *   <li>Every starting rule proposes changes ({@link #RULES}, in order of precedence: check-in first, then recovery,
 *       sleep, volume and restoring volume).</li>
 *   <li>Guardrails drop what must never be suggested: more load on a red day; anything but a reduction in race week
 *       or taper. (Key sessions are moved rather than deleted, and weekly targets held, by the rules themselves.)</li>
 *   <li>One suggestion per session: when several rules propose different changes, the most conservative wins and the
 *       reasons say the signals disagreed.</li>
 * </ol>
 */
public final class RulesEngine {
    /** Version of the rules; bump it when a rule changes, so stored suggestions say which rules made them. */
    public static final String RULES_VERSION = "r1";

    static final List<Rule> RULES = List.of(new IllnessRule(), new NiggleRule(), new RedDayRule(), new SleepDebtRule(),
            new YellowStreakRule(), new VolumeRule(), new RestoreRule());
    private static final Set<String> CHECKIN_RULES = Set.of("illness", "niggle");

    private RulesEngine() {}

    public static Result evaluate(Input in, EngineSettings s) {
        var c = new Context(in, s);
        var notes = new ArrayList<String>();
        var st = in.todayState();
        if (st == null) notes.add("No signals for today yet: the recovery, sleep and check-in rules were skipped.");
        else if (st.beforeHrv()) notes.add("No overnight HRV yet on this day, so there is no readiness: the recovery rules were skipped.");
        else if (st.band() == null) notes.add("No recovery score for today yet: the red and yellow rules were skipped.");
        if (st != null && st.toleranceKmPerWeek() == null) notes.add("No running tolerance yet: the volume rule used the running ACWR only.");

        var all = new ArrayList<Proposal>();
        for (Rule r : RULES) all.addAll(r.apply(c));
        var kept = new ArrayList<Proposal>();
        for (Proposal p : all) {
            String why = guardrail(c, p);
            if (why == null) kept.add(p);
            else notes.add("Guardrail: the " + p.rule() + " rule's " + p.action().name().toLowerCase(java.util.Locale.ROOT) + " for "
                    + label(p.session()) + " on " + day(p.session().date()) + " was dropped (" + why + ").");
        }
        return new Result(resolve(c, kept), healthMessages(c), notes);
    }

    /** Null when the proposal may stand; otherwise why a guardrail drops it. */
    static String guardrail(Context c, Proposal p) {
        var st = c.todayState();
        boolean redToday = st != null && "RED".equals(st.band());
        if (redToday && (increasesLoad(p) || bringsHardToday(c, p))) return "never more load on a red day";
        boolean prot = c.protectedSession(p.session()) || (p.counterpart() != null && c.protectedSession(p.counterpart()))
                || c.protectedDay(p.after().date());
        if (prot && (p.action() == Action.SWAP || p.action() == Action.MOVE || p.action() == Action.ADD || increasesLoad(p)))
            return "race week and taper get only reductions";
        return null;
    }

    static boolean increasesLoad(Proposal p) {
        if (p.action() == Action.ADD) return true;
        if (p.action() != Action.REDUCE && p.action() != Action.REPLACE) return false;
        Session b = p.session();
        Change a = p.after();
        return hardness(a.type(), a.demand()) > hardness(b.type(), b.demand())
                || (a.distanceKm() != null && b.distanceKm() != null && a.distanceKm() > b.distanceKm() + 1e-9)
                || (a.durationMin() != null && b.durationMin() != null && a.durationMin() > b.durationMin());
    }

    private static boolean bringsHardToday(Context c, Proposal p) {
        if (p.action() == Action.SWAP && p.counterpartAfter() != null && p.counterpartAfter().date().equals(c.today))
            return hardness(p.counterpartAfter().type(), p.counterpartAfter().demand()) >= 2;
        return (p.action() == Action.MOVE) && p.after().date().equals(c.today) && hardness(p.after().type(), p.after().demand()) >= 2;
    }

    /** rest 0, cross-training or easy 1, supporting 2, key 3. */
    static int hardness(String type, String demand) {
        if ("rest".equals(type)) return 0;
        if ("KEY".equals(demand)) return 3;
        if ("SUPPORTING".equals(demand)) return 2;
        return 1;
    }

    /** Most conservative first: what the session becomes, then what is left on its day, then its time, then rule order. */
    private static Comparator<Proposal> conservative(Context c) {
        return Comparator.<Proposal>comparingInt(p -> hardness(p.after().type(), p.after().demand()))
                .thenComparingInt(p -> onOriginalDay(p))
                .thenComparingDouble(p -> volume(p.after()))
                .thenComparingInt(p -> RULES.indexOf(RULES.stream().filter(r -> r.id().equals(p.rule())).findFirst().orElseThrow()));
    }

    private static int onOriginalDay(Proposal p) {
        if (p.action() == Action.MOVE) return 0;
        if (p.action() == Action.SWAP) return hardness(p.counterpart().type(), p.counterpart().demand());
        return hardness(p.after().type(), p.after().demand());
    }

    private static double volume(Change a) {
        return a.durationMin() != null ? a.durationMin() : a.distanceKm() != null ? a.distanceKm() * 6 : 0;
    }

    private static List<Proposal> resolve(Context c, List<Proposal> proposals) {
        var bySession = new LinkedHashMap<Long, List<Proposal>>();
        for (Proposal p : proposals) bySession.computeIfAbsent(p.session().id(), k -> new ArrayList<>()).add(p);
        var winners = new ArrayList<Proposal>();
        for (var group : bySession.values()) {
            group.sort(conservative(c));
            Proposal best = group.get(0);
            var others = new LinkedHashMap<String, Proposal>();
            for (Proposal p : group.subList(1, group.size())) if (!p.rule().equals(best.rule())) others.putIfAbsent(p.rule(), p);
            winners.add(withConflictNote(c, best, others));
        }
        // A session is in at most one suggestion: earlier days first, so a swap today claims its partner.
        winners.sort(Comparator.comparing((Proposal p) -> p.session().date()).thenComparing(p -> p.session().id()));
        Set<Long> claimed = new HashSet<>();
        var out = new ArrayList<Proposal>();
        for (Proposal p : winners) {
            if (claimed.contains(p.session().id()) || (p.counterpart() != null && claimed.contains(p.counterpart().id()))) continue;
            claimed.add(p.session().id());
            if (p.counterpart() != null) claimed.add(p.counterpart().id());
            out.add(p);
        }
        return out;
    }

    /** Guardrail: when signals conflict, the conservative option is chosen and the reasons say so (at most 3 reasons). */
    private static Proposal withConflictNote(Context c, Proposal best, Map<String, Proposal> others) {
        var st = c.todayState();
        Reason note = null;
        if (!others.isEmpty()) {
            var names = new ArrayList<String>();
            for (Proposal o : others.values()) names.add(o.reasons().get(0).text());
            note = reason("conflict.conservative", "Other signals point elsewhere (" + String.join(" ", names).replaceAll("\\.\\s*$", "")
                    + "); the more conservative option was chosen.", "otherRules", String.join(",", others.keySet()));
        } else if (CHECKIN_RULES.contains(best.rule()) && st != null && "GREEN".equals(st.band())) {
            note = reason("conflict.checkin", "Recovery is green (" + st.recoveryPct() + "%), but the check-in says otherwise; the check-in "
                    + "comes first, the conservative option.", "recoveryPct", st.recoveryPct());
        }
        if (note == null) return best;
        var reasons = new ArrayList<>(best.reasons().subList(0, Math.min(2, best.reasons().size())));
        reasons.add(note);
        return best.withReasons(reasons);
    }

    /** SPEC.md niggle rule: a health message when a niggle persists {@code niggle.healthMessageDays}+ days. */
    static List<Reason> healthMessages(Context c) {
        var st = c.todayState();
        var out = new ArrayList<Reason>();
        if (st == null || st.niggles() == null) return out;
        for (var n : st.niggles()) {
            if (n.persistDays() < c.s.niggleHealthMessageDays()) continue;
            out.add(reason("health.niggle", "The " + n.location() + " niggle has lasted " + n.persistDays() + " days (since " + day(n.since())
                    + "). If it does not settle, consider having it checked by a physio or doctor.", "location", n.location(),
                    "persistDays", n.persistDays(), "since", n.since().toString()));
        }
        return out;
    }

    /** For tests and the service: the window the run reviews. */
    public static LocalDate lastDay(LocalDate today, EngineSettings s) {
        return today.plusDays(s.horizonDays() - 1);
    }
}
