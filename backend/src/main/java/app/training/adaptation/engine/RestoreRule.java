package app.training.adaptation.engine;

import static app.training.adaptation.engine.Context.day;
import static app.training.adaptation.engine.Context.details;
import static app.training.adaptation.engine.Context.num;
import static app.training.adaptation.engine.Context.pct;
import static app.training.adaptation.engine.Context.reason;
import static app.training.adaptation.engine.Context.round1;

import app.training.adaptation.engine.Model.Action;
import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.Confidence;
import app.training.adaptation.engine.Model.Proposal;
import app.training.adaptation.engine.Model.Reason;
import app.training.adaptation.engine.Model.Session;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SPEC.md: "Green recovery + load well under plan for 2+ weeks → suggest restoring missed volume gradually, never
 * more than one progression step." (ADR 0022 Q6.) Recovery green today; in each of the last {@code restore.weeks}
 * full Monday-Sunday weeks the km run were below {@code underShare} of the km planned (sessions done, planned or
 * skipped; a replaced session is counted through its replacement). Then the easy runs of the next 7 days get together
 * {@code stepShare} of their week's planned km more (ADD on those sessions), never past tolerance. Not while a niggle
 * or illness is open, not when the volume rule would trim, and only one step per 7 days.
 */
final class RestoreRule implements Rule {
    @Override
    public String id() {
        return "restore";
    }

    @Override
    public List<Proposal> apply(Context c) {
        var st = c.todayState();
        if (st == null || !"GREEN".equals(st.band()) || c.in.restoreStepRecently()) return List.of();
        if (st.niggles() != null && !st.niggles().isEmpty()) return List.of();
        LocalDate lastIll = c.in.illnessDays().stream().filter(d -> !d.isAfter(c.today)).max(LocalDate::compareTo).orElse(null);
        if (lastIll != null && lastIll.plusDays(c.s.illnessActiveDays() + c.s.illnessReturnDays()).isAfter(c.today)) return List.of();
        Double limit = Volume.limit(c);
        if (limit == null) return List.of();
        Double acwr = Volume.runningAcwr(c);
        var now = Volume.max(c, Map.of());
        if (now.km() > limit || (acwr != null && acwr > c.s.acwrThreshold())) return List.of();

        LocalDate thisMonday = Model.monday(c.today);
        var weeks = new ArrayList<String>();
        for (int k = 1; k <= c.s.restoreWeeks(); k++) {
            LocalDate from = thisMonday.minusWeeks(k), to = from.plusDays(6);
            double planned = 0, done = 0;
            for (Session x : c.in.sessions()) {
                if (!"REPLACED".equals(x.status()) && x.running() && !x.date().isBefore(from) && !x.date().isAfter(to)) planned += x.km();
            }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) done += c.in.runKmByDay().getOrDefault(d, 0.0);
            if (planned <= 0 || done >= planned * c.s.restoreUnderShare()) return List.of();
            weeks.add(num(done) + " of " + num(planned) + " km (week of " + day(from) + ")");
        }

        var easy = c.changeable().stream().filter(x -> x.easyRun() && x.km() > 0 && !c.protectedSession(x)).toList();
        double comingPlanned = c.changeable().stream().filter(Session::running).mapToDouble(Session::km).sum();
        double easyKm = easy.stream().mapToDouble(Session::km).sum();
        if (easy.isEmpty() || easyKm <= 0) return List.of();
        double step = Math.min(comingPlanned * c.s.restoreStepShare(), st.toleranceKmPerWeek() - now.km());
        if (step < 0.5) return List.of();

        Reason green = reason("restore.under", "Recovery is green today (" + st.recoveryPct() + "%) and running was well under plan: "
                + String.join("; ", weeks) + ".", "recoveryPct", st.recoveryPct(), "underShare", c.s.restoreUnderShare());
        var out = new ArrayList<Proposal>();
        for (Session x : easy) {
            double add = round1(step * x.km() / easyKm);
            if (add < 0.1) continue;
            double newKm = round1(x.km() + add);
            Integer min = x.durationMin() == null ? null : (int) Math.round(x.durationMin() * newKm / x.km());
            Change change = Change.of(x).withVolume(newKm, min).withDescription("Engine: " + num(x.km()) + " → " + num(newKm)
                    + " km to restore missed volume gradually.\n" + Context.nz(x.description()));
            out.add(new Proposal(id(), Action.ADD, x, change, null, null, List.of(green,
                    reason("restore.step", "One progression step: " + num(step) + " km more over the next " + c.s.horizonDays() + " days ("
                            + pct(c.s.restoreStepShare()) + " of the planned km), spread over the easy runs, staying within the tolerance of "
                            + num(st.toleranceKmPerWeek()) + " km/week.", "stepKm", round1(step), "addKm", add,
                            "toleranceKm", st.toleranceKmPerWeek())),
                    Confidence.MEDIUM, details("stepKm", round1(step), "addKm", add)));
        }
        return out;
    }
}
