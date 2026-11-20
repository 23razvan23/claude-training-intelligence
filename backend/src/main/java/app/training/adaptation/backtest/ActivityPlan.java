package app.training.adaptation.backtest;

import app.training.adaptation.engine.Model;
import app.training.adaptation.signals.SignalRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * No plan existed in the past, so the backtest uses what the athlete actually did as "the plan" (ADR 0023 Q1): every
 * Garmin activity of a kind that counts becomes one session on its local date, classified into a session type by
 * {@link #classify}, with the demand the settings give that type. Walks, breathwork, yoga and unnamed activities are
 * left out. Pure.
 *
 * <p>This is hindsight about the plan, by construction: a skipped or shortened week shows up as a lighter plan, and
 * the rule that restores missed volume can never fire, because what was run always equals what was "planned".
 */
public final class ActivityPlan {
    private ActivityPlan() {}

    /** One Garmin activity as the plan reads it. Zone minutes are null when the activity had no usable heart rate. */
    public record PlanActivity(long id, LocalDate date, LocalDate cycleDate, String sport, String sourceSport, Integer durationS,
                               Double distanceM, Double zone4Min, Double zone5Min) {
        Double zone4PlusMin() {
            return zone4Min == null || zone5Min == null ? null : zone4Min + zone5Min;
        }
    }

    /** The session type an activity stands for, or null when it is left out of the plan. */
    public static String classify(PlanActivity a, BacktestParams.Classification c, java.util.Set<LocalDate> races) {
        if ("running".equals(a.sport())) {
            if (races.contains(a.date())) return "race";
            boolean isLong = (a.durationS() != null && a.durationS() >= c.longMin() * 60)
                    || (a.distanceM() != null && a.distanceM() >= c.longKm() * 1000);
            Double z4plus = a.zone4PlusMin();
            if (isLong) return z4plus != null && z4plus >= c.fastFinishZone4PlusMin() ? "long_fast_finish" : "long";
            if (a.zone5Min() != null && a.zone5Min() >= c.vo2maxZone5Min()) return "vo2max";
            if (z4plus != null && z4plus >= c.thresholdZone4PlusMin()) return "threshold";
            return "easy";
        }
        if (c.crossSports().contains(a.sport())) return "cross";
        if (a.sourceSport() != null && c.crossSourceSports().contains(a.sourceSport().toLowerCase(Locale.ROOT))) return "cross";
        return null;
    }

    /**
     * The plan as the engine sees it on the morning of {@code morning}: sessions dated {@code from} … {@code to}; those
     * before the morning are DONE, the rest PLANNED. Sorted by date, then activity id.
     */
    public static List<Model.Session> sessions(List<PlanActivity> activities, LocalDate morning, LocalDate from, LocalDate to,
                                               BacktestParams p, Function<String, String> demandFor) {
        var races = new HashSet<>(p.races());
        var out = new ArrayList<Model.Session>();
        for (var a : activities) {
            if (a.date().isBefore(from) || a.date().isAfter(to)) continue;
            String type = classify(a, p.classification(), races);
            if (type == null) continue;
            Double km = a.distanceM() == null || a.distanceM() <= 0 ? null : Math.round(a.distanceM() / 100.0) / 10.0;
            Integer min = a.durationS() == null ? null : (int) Math.round(a.durationS() / 60.0);
            out.add(new Model.Session(a.id(), null, a.date(), type, demandFor.apply(type), title(type, a), description(a), km, min, null,
                    a.date().isBefore(morning) ? "DONE" : "PLANNED", false, null));
        }
        out.sort(java.util.Comparator.comparing(Model.Session::date).thenComparing(Model.Session::id));
        return out;
    }

    /** The same sessions in the shape the signals' "session demand" reads, per day. */
    public static Map<LocalDate, List<SignalRepository.PlannedSession>> perDay(List<Model.Session> sessions) {
        var out = new java.util.HashMap<LocalDate, List<SignalRepository.PlannedSession>>();
        for (var s : sessions) {
            out.computeIfAbsent(s.date(), k -> new ArrayList<>())
                    .add(new SignalRepository.PlannedSession(s.id(), s.type(), s.demand(), null, s.distanceKm(), s.durationMin(), s.status()));
        }
        return out;
    }

    private static String title(String type, PlanActivity a) {
        String name = switch (type) {
            case "race" -> "Race";
            case "long" -> "Long run";
            case "long_fast_finish" -> "Long run, fast finish";
            case "vo2max" -> "VO2max run";
            case "threshold" -> "Threshold run";
            case "easy" -> "Easy run";
            default -> "Cross-training";
        };
        return name + " (Garmin " + (a.sourceSport() == null ? a.sport() : a.sourceSport()) + ")";
    }

    private static String description(PlanActivity a) {
        var parts = new ArrayList<String>();
        if (a.distanceM() != null && a.distanceM() > 0) parts.add(String.format(Locale.ROOT, "%.1f km", a.distanceM() / 1000));
        if (a.durationS() != null) parts.add(Math.round(a.durationS() / 60.0) + " min");
        if (a.zone4PlusMin() != null) parts.add(String.format(Locale.ROOT, "%.0f min above 80%% HRR (%.0f above 90%%)", a.zone4PlusMin(), a.zone5Min()));
        else if ("running".equals(a.sport())) parts.add("no heart-rate zones");
        return "What was actually done: " + String.join(", ", parts) + ".";
    }
}
