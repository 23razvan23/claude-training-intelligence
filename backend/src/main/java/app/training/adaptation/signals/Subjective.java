package app.training.adaptation.signals;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Check-in flags per day (SPEC.md Module 3 "Subjective"; ADR 0021 D9), from the current check-ins
 * ({@code subjective_current}, ADR 0018 D6). Pure.
 * <ul>
 *   <li>RPE of the day (whole-day scope), the highest session/activity RPE, the highest soreness, illness and injury
 *       that day, and the number of days with illness or injury in the last 7 days.</li>
 *   <li>Niggles per body area: an episode starts with a niggle at that area and lasts until the athlete marks it gone,
 *       or until it hasn't been reported for {@code staleDays} [configurable, 7] (nobody reports "gone" every time).
 *       {@code persistDays} counts from the first report, for the rule "niggle persists 3+ days".</li>
 *   <li>{@code flagged}: a niggle (not marked gone) or an injury was reported that day; such days make a 4-week window
 *       unusable for running tolerance.</li>
 * </ul>
 */
public final class Subjective {
    private Subjective() {}

    public record Checkin(LocalDate date, boolean wholeDay, Integer rpe, Integer soreness, String niggleLocation, Integer niggleSeverity,
                          boolean niggleResolved, boolean injury, boolean illness) {}

    public record Niggle(String location, LocalDate since, LocalDate lastReported, int persistDays, Integer severity) {}

    public record Day(int checkins, Integer dayRpe, Integer maxSessionRpe, Integer soreness, boolean illness, boolean injury,
                      int illnessDaysLast7, int injuryDaysLast7, List<Niggle> niggles, boolean flagged) {}

    private static final class Episode {
        LocalDate since;
        LocalDate last;
        Integer severity;
    }

    public static List<Day> compute(List<LocalDate> grid, List<Checkin> checkins, int staleDays) {
        var byDay = new TreeMap<LocalDate, List<Checkin>>();
        for (var c : checkins) byDay.computeIfAbsent(c.date(), k -> new ArrayList<>()).add(c);
        var episodes = new HashMap<String, Episode>();
        // Check-ins before the grid still open episodes.
        if (!grid.isEmpty()) for (var e : byDay.headMap(grid.get(0)).entrySet()) apply(episodes, e.getKey(), e.getValue(), staleDays);

        var illnessDays = new java.util.HashSet<LocalDate>();
        var injuryDays = new java.util.HashSet<LocalDate>();
        for (var c : checkins) {
            if (c.illness()) illnessDays.add(c.date());
            if (c.injury()) injuryDays.add(c.date());
        }
        var out = new ArrayList<Day>(grid.size());
        for (LocalDate d : grid) {
            var rows = byDay.getOrDefault(d, List.of());
            apply(episodes, d, rows, staleDays);
            Integer dayRpe = null, maxSession = null, soreness = null;
            boolean illness = false, injury = false, flagged = false;
            for (var c : rows) {
                if (c.wholeDay() && c.rpe() != null) dayRpe = c.rpe();
                if (!c.wholeDay() && c.rpe() != null) maxSession = maxSession == null ? c.rpe() : Math.max(maxSession, c.rpe());
                if (c.soreness() != null) soreness = soreness == null ? c.soreness() : Math.max(soreness, c.soreness());
                illness |= c.illness();
                injury |= c.injury();
                flagged |= c.injury() || (c.niggleLocation() != null && !c.niggleResolved());
            }
            int ill7 = 0, inj7 = 0;
            for (int k = 0; k < 7; k++) {
                if (illnessDays.contains(d.minusDays(k))) ill7++;
                if (injuryDays.contains(d.minusDays(k))) inj7++;
            }
            var niggles = new ArrayList<Niggle>();
            for (Map.Entry<String, Episode> e : episodes.entrySet()) {
                var ep = e.getValue();
                if (ChronoUnit.DAYS.between(ep.last, d) >= staleDays) continue;
                niggles.add(new Niggle(e.getKey(), ep.since, ep.last, (int) ChronoUnit.DAYS.between(ep.since, d) + 1, ep.severity));
            }
            niggles.sort(Comparator.comparing(Niggle::location));
            out.add(new Day(rows.size(), dayRpe, maxSession, soreness, illness, injury, ill7, inj7, List.copyOf(niggles), flagged));
        }
        return out;
    }

    private static void apply(Map<String, Episode> episodes, LocalDate d, List<Checkin> rows, int staleDays) {
        for (var c : rows) {
            if (c.niggleLocation() == null) continue;
            if (c.niggleResolved()) {
                episodes.remove(c.niggleLocation());
                continue;
            }
            var ep = episodes.get(c.niggleLocation());
            if (ep == null || ChronoUnit.DAYS.between(ep.last, d) >= staleDays) {
                ep = new Episode();
                ep.since = d;
                episodes.put(c.niggleLocation(), ep);
            }
            ep.last = d;
            if (c.niggleSeverity() != null) ep.severity = c.niggleSeverity();
        }
    }
}
