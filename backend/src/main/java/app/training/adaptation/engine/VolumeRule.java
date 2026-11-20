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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * SPEC.md: "Running volume > tolerance + 10%, or running ACWR > 1.3 → trim easy runs first, then shorten the long run;
 * never add volume." (ADR 0022 Q5.)
 * <ul>
 *   <li><b>Volume</b>: for each day of the run, the 7 days ending on it (km run + km planned). Where that is above
 *       tolerance × (1 + margin), the excess is taken off the easy runs in that window (each by at most
 *       {@code maxEasyCutShare}), then off the long runs (at most {@code maxLongCutShare}). Medium confidence, high
 *       when the running ACWR agrees.</li>
 *   <li><b>ACWR alone</b> (yesterday's running ACWR, since today's load is not finished): one signal among several and
 *       disputed as an injury predictor, so it never decides on its own: easy runs are trimmed by
 *       {@code acwrOnlyEasyCutShare} with low confidence, and the reason says so.</li>
 * </ul>
 */
final class VolumeRule implements Rule {
    @Override
    public String id() {
        return "volume";
    }

    @Override
    public List<Proposal> apply(Context c) {
        Double limit = Volume.limit(c);
        Double acwr = Volume.runningAcwr(c);
        boolean acwrHigh = acwr != null && acwr > c.s.acwrThreshold();
        var before = Volume.max(c, Map.of());
        boolean over = limit != null && before.km() > limit + 0.05;
        if (!over && !acwrHigh) return List.of();

        Map<Long, Double> km = new LinkedHashMap<>();
        Map<Long, Session> trimmed = new LinkedHashMap<>();
        if (over) {
            for (LocalDate d = c.today; !d.isAfter(c.end); d = d.plusDays(1)) {
                double excess = Volume.window(c, d, km) - limit;
                if (excess <= 0.05) continue;
                excess = cut(c, d, km, trimmed, excess, Session::easyRun, c.s.maxEasyCutShare());
                if (excess > 0.05) cut(c, d, km, trimmed, excess, Session::longRun, c.s.maxLongCutShare());
            }
        } else {
            for (Session x : c.changeable()) {
                if (!x.easyRun() || x.distanceKm() == null || x.km() <= 0) continue;
                km.put(x.id(), x.km() * (1 - c.s.acwrOnlyEasyCutShare()));
                trimmed.put(x.id(), x);
            }
        }
        if (trimmed.isEmpty()) return List.of();
        var after = Volume.max(c, km);
        long noDistance = Volume.runsWithoutDistance(c);

        var out = new ArrayList<Proposal>();
        for (Session x : trimmed.values()) {
            double newKm = round1(km.get(x.id()));
            if (x.km() - newKm < 0.05) continue;
            Integer min = x.durationMin() == null ? null : (int) Math.round(x.durationMin() * newKm / x.km());
            Change change = Change.of(x).withVolume(newKm, min).withDescription("Engine: " + num(x.km()) + " → " + num(newKm)
                    + " km to keep this week's running within tolerance.\n" + Context.nz(x.description()));
            var reasons = new ArrayList<Reason>();
            Confidence conf;
            if (over) {
                reasons.add(reason("volume.over", "Running in the 7 days to " + day(before.end()) + " would be " + num(before.km())
                        + " km, above the tolerance of " + num(c.todayState().toleranceKmPerWeek()) + " km/week + "
                        + pct(c.s.toleranceMargin()) + " (" + num(limit) + " km).", "windowEnd", before.end().toString(),
                        "km", round1(before.km()), "toleranceKm", c.todayState().toleranceKmPerWeek(), "limitKm", round1(limit)));
                reasons.add(reason(x.longRun() ? "volume.trimLong" : "volume.trimEasy", (x.longRun()
                        ? "Easy runs alone could not bring it down, so the long run is shortened by "
                        : "Easy runs are trimmed first: this one by ") + num(x.km() - newKm) + " km; no volume is added.",
                        "cutKm", round1(x.km() - newKm)));
                if (acwrHigh) reasons.add(acwrReason(c, acwr, true));
                else if (noDistance > 0) reasons.add(reason("volume.noDistance", noDistance + " planned run(s) have no distance and are not "
                        + "counted, so the real total may be higher.", "count", noDistance));
                conf = acwrHigh ? Confidence.HIGH : Confidence.MEDIUM;
            } else {
                reasons.add(acwrReason(c, acwr, false));
                reasons.add(reason("volume.acwrOnly", "ACWR is one signal among several and its link to injury is disputed, so this is a "
                        + "light trim (" + pct(c.s.acwrOnlyEasyCutShare()) + " of easy runs) with low confidence."));
                if (limit != null) reasons.add(reason("volume.within", "Planned running stays within tolerance (" + num(before.km())
                        + " of " + num(limit) + " km in the fullest 7 days).", "km", round1(before.km()), "limitKm", round1(limit)));
                conf = Confidence.LOW;
            }
            out.add(new Proposal(id(), Action.REDUCE, x, change, null, null, reasons, conf,
                    details("trigger", over ? (acwrHigh ? "volume+acwr" : "volume") : "acwr", "maxWindowKm", round1(before.km()),
                            "maxWindowKmAfter", round1(after.km()), "limitKm", limit == null ? null : round1(limit), "runningAcwr", acwr)));
        }
        return out;
    }

    private static Reason acwrReason(Context c, double acwr, boolean alsoVolume) {
        return reason("volume.acwr", "Running ACWR is " + num2(acwr) + ", above " + num2(c.s.acwrThreshold())
                + (alsoVolume ? "; it agrees with the volume, but is one signal among several." : ": recent running load rose fast "
                + "compared with the last 4 weeks."), "runningAcwr", acwr, "threshold", c.s.acwrThreshold());
    }

    private static String num2(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Takes {@code excess} km off the matching sessions in the window ending on {@code end}, in proportion; returns what is left. */
    private static double cut(Context c, LocalDate end, Map<Long, Double> km, Map<Long, Session> trimmed, double excess,
                              Predicate<Session> which, double maxShare) {
        var room = new LinkedHashMap<Session, Double>();
        double total = 0;
        for (Session x : c.changeable()) {
            if (x.date().isAfter(end) || x.date().isBefore(end.minusDays(6)) || !which.test(x) || x.km() <= 0) continue;
            double floor = x.km() * (1 - maxShare);
            double r = km.getOrDefault(x.id(), x.km()) - floor;
            if (r > 1e-9) {
                room.put(x, r);
                total += r;
            }
        }
        if (total <= 0) return excess;
        double share = Math.min(1, excess / total);
        for (var e : room.entrySet()) {
            km.put(e.getKey().id(), km.getOrDefault(e.getKey().id(), e.getKey().km()) - e.getValue() * share);
            trimmed.put(e.getKey().id(), e.getKey());
        }
        return excess - total * share;
    }
}
