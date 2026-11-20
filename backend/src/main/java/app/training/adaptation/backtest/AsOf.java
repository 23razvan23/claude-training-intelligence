package app.training.adaptation.backtest;

import app.training.adaptation.signals.SignalCalculator;
import app.training.adaptation.signals.SignalRepository;
import app.training.adaptation.signals.Subjective;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.function.Predicate;

/**
 * Picks the rows that existed by a past morning (ADR 0023 D2, "no look-ahead"). Pure. The morning of day D is:
 * <ul>
 *   <li><b>Body data</b> (loads, running, sleep, scores): everything of the cycles before D, plus D's own night and
 *       score. D's load and running are not counted: they happen after waking, and the live engine also reads only
 *       yesterday's load (ADR 0021 D5). Historical rows were bulk-imported in November 2026, so their import time
 *       says nothing about when they existed; their own dates do.</li>
 *   <li><b>Rows with a typing time</b> (manual check-ins, tolerance overrides): only those saved before the morning
 *       cut-off (09:00 local on D, [configurable]). "Latest wins" is applied among those rows only, so a correction
 *       typed later cannot reach back.</li>
 *   <li><b>Garmin RPE seed rows</b> (ADR 0020 amendment): entered in Garmin right after the activity, so they count
 *       from the morning after its cycle, not from the day we imported them.</li>
 * </ul>
 */
public final class AsOf {
    private AsOf() {}

    /** Everything stored for an athlete, read once per backtest run; {@link #inputs} filters it per morning. */
    public record Data(ZoneId zone, LocalDate firstFeatureDay, Map<LocalDate, SignalRepository.TotalLoad> totals,
                       Map<LocalDate, SignalRepository.Running> running, Map<LocalDate, SignalRepository.Score> scores,
                       Map<LocalDate, Integer> slept, NavigableSet<LocalDate> hrvDays, List<CheckinRow> checkins,
                       List<SignalRepository.OverrideRow> overrides, List<ActivityPlan.PlanActivity> activities,
                       List<Revision> wellnessRevisions) {}

    /**
     * One subjective_log row with what decides when it existed: {@code createdAt}, its {@code source}, and for a row
     * about an activity, the activity's cycle day ({@code activityCycleDate}, null otherwise).
     */
    public record CheckinRow(long id, LocalDate logDate, String scopeKey, Instant createdAt, String source, LocalDate activityCycleDate,
                             Subjective.Checkin checkin) {}

    /** A Garmin daily summary stored again with new values (ADR 0010): which day, and when the new version arrived. */
    public record Revision(LocalDate calendarDate, Instant ingestedAt) {}

    /** The morning cut-off as an instant: {@code morning} at the cut-off time in the athlete's timezone. */
    public static Instant cutoff(LocalDate morning, BacktestParams p, ZoneId zone) {
        return morning.atTime(p.morningCutoff()).atZone(zone).toInstant();
    }

    /** Did this row exist by the morning of {@code morning}? */
    static boolean available(CheckinRow r, LocalDate morning, Instant cutoff) {
        if ("GARMIN_SEED".equals(r.source())) {
            LocalDate day = r.activityCycleDate() != null ? r.activityCycleDate() : r.logDate();
            return day.isBefore(morning);
        }
        return !r.createdAt().isAfter(cutoff);
    }

    /**
     * The check-ins that counted on that morning: the rows that existed, then "latest wins" per day and scope exactly as
     * the view subjective_current orders them (ADR 0018 D6: newest created_at, ties the higher id). Oldest first, as
     * {@code SignalRepository.checkins} returns them.
     */
    public static List<Subjective.Checkin> checkins(List<CheckinRow> rows, LocalDate morning, Instant cutoff) {
        return current(rows, r -> available(r, morning, cutoff));
    }

    /** "Latest wins" over the rows that pass {@code keep}; with every row kept it equals subjective_current. */
    static List<Subjective.Checkin> current(List<CheckinRow> rows, Predicate<CheckinRow> keep) {
        var newest = new LinkedHashMap<String, CheckinRow>();
        for (var r : rows) {
            if (!keep.test(r)) continue;
            String k = r.logDate() + "|" + r.scopeKey();
            var old = newest.get(k);
            if (old == null || r.createdAt().isAfter(old.createdAt()) || (r.createdAt().equals(old.createdAt()) && r.id() > old.id())) newest.put(k, r);
        }
        return newest.values().stream()
                .sorted(Comparator.comparing(CheckinRow::logDate).thenComparing(CheckinRow::createdAt).thenComparingLong(CheckinRow::id))
                .map(CheckinRow::checkin).toList();
    }

    /**
     * The signals' inputs as they stood on the morning of {@code morning}, for the days {@code firstFeatureDay} …
     * {@code morning}. {@code sessions} is the stand-in plan (activities, ADR 0023 Q1), per day.
     */
    public static SignalCalculator.Inputs inputs(Data d, LocalDate morning, BacktestParams p,
                                                 Map<LocalDate, List<SignalRepository.PlannedSession>> sessions) {
        Instant cutoff = cutoff(morning, p, d.zone());
        LocalDate firstHrv = d.hrvDays().isEmpty() || d.hrvDays().first().isAfter(morning) ? null : d.hrvDays().first();
        var overrides = new ArrayList<SignalRepository.OverrideRow>();
        for (var o : d.overrides()) if (!o.createdAt().isAfter(cutoff)) overrides.add(o);
        return new SignalCalculator.Inputs(d.firstFeatureDay(), morning, before(d.totals(), morning), before(d.running(), morning),
                upTo(d.scores(), morning), upTo(d.slept(), morning), firstHrv, checkins(d.checkins(), morning, cutoff),
                upTo(sessions, morning), overrides, d.zone());
    }

    /** Running km per day the engine may see that morning: the days before it (today's run happens after waking). */
    public static Map<LocalDate, Double> runKm(Data d, LocalDate morning) {
        var out = new HashMap<LocalDate, Double>();
        d.running().forEach((day, r) -> {
            if (day.isBefore(morning)) out.put(day, r.km());
        });
        return out;
    }

    /** Notes for a morning whose Garmin summary (that day's or the day before's) was corrected after the cut-off. */
    public static List<String> lateRevisions(Data d, LocalDate morning, BacktestParams p) {
        Instant cutoff = cutoff(morning, p, d.zone());
        var out = new ArrayList<String>();
        for (var r : d.wellnessRevisions()) {
            boolean relevant = r.calendarDate().equals(morning) || r.calendarDate().equals(morning.minusDays(1));
            if (relevant && r.ingestedAt().isAfter(cutoff)) {
                out.add("Garmin sent a corrected daily summary for " + r.calendarDate() + " after this morning (imported "
                        + r.ingestedAt().atZone(d.zone()).toLocalDate() + "); the replay uses the corrected values (ADR 0023 D3).");
            }
        }
        return out.stream().distinct().toList();
    }

    private static <V> Map<LocalDate, V> before(Map<LocalDate, V> m, LocalDate morning) {
        var out = new HashMap<LocalDate, V>();
        m.forEach((k, v) -> {
            if (k.isBefore(morning)) out.put(k, v);
        });
        return out;
    }

    private static <V> Map<LocalDate, V> upTo(Map<LocalDate, V> m, LocalDate morning) {
        var out = new HashMap<LocalDate, V>();
        m.forEach((k, v) -> {
            if (!k.isAfter(morning)) out.put(k, v);
        });
        return out;
    }
}
