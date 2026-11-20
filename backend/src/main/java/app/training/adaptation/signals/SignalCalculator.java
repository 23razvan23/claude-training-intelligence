package app.training.adaptation.signals;

import app.training.adaptation.settings.AppSettings;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The arithmetic of the signals (stage 2-2, ADR 0021), separated from reading the database so the live recompute
 * ({@link SignalService}) and the backtest's replay (stage 2-4, ADR 0023) run exactly the same code: the backtest
 * only hands it the rows that existed on a past morning. Pure: the same inputs and settings give the same days.
 */
public final class SignalCalculator {
    private SignalCalculator() {}

    /**
     * Everything the signals read, for the days {@code first} … {@code last}.
     * <ul>
     *   <li>{@code totals}, {@code running}: daily load and running per cycle day (a day absent = unknown / no run).</li>
     *   <li>{@code scores}: Module 1 scores of the ACTIVE model per day (empty without a model).</li>
     *   <li>{@code slept}: minutes asleep per night; {@code firstHrv}: the first day with overnight HRV (or null).</li>
     *   <li>{@code checkins}: the current check-ins (latest wins already applied), oldest first.</li>
     *   <li>{@code sessions}: planned sessions that still count, per day.</li>
     *   <li>{@code overrides}: the coach's tolerance overrides, oldest first; {@code zone}: the athlete's timezone.</li>
     * </ul>
     */
    public record Inputs(LocalDate first, LocalDate last, Map<LocalDate, SignalRepository.TotalLoad> totals,
                         Map<LocalDate, SignalRepository.Running> running, Map<LocalDate, SignalRepository.Score> scores,
                         Map<LocalDate, Integer> slept, LocalDate firstHrv, List<Subjective.Checkin> checkins,
                         Map<LocalDate, List<SignalRepository.PlannedSession>> sessions, List<SignalRepository.OverrideRow> overrides,
                         ZoneId zone) {}

    /** One {@link DaySignals} per day from {@code first} to {@code last}; empty when {@code first} is after {@code last}. */
    public static List<DaySignals> compute(Inputs in, AppSettings current) {
        if (in.first() == null || in.first().isAfter(in.last())) return List.of();
        var s = SignalSettings.from(current);
        boolean banister = SignalSettings.BANISTER.equals(s.dailyLoad());
        var grid = new ArrayList<LocalDate>();
        var inputs = new ArrayList<LoadSignals.DayInput>();
        for (LocalDate d = in.first(); !d.isAfter(in.last()); d = d.plusDays(1)) {
            grid.add(d);
            var t = in.totals().get(d);
            var r = in.running().get(d);
            Double total = t == null ? null : banister ? t.banister() : t.edwards();
            Double runLoad = r == null ? null : banister ? r.banister() : r.edwards();
            inputs.add(new LoadSignals.DayInput(d, total, runLoad, r == null ? 0 : r.km(), r == null ? 0 : r.minutes()));
        }
        var load = LoadSignals.compute(inputs, s);

        var readinessIn = new ArrayList<Readiness.Input>();
        var sleepIn = new ArrayList<SleepDebt.Input>();
        for (LocalDate d : grid) {
            var sc = in.scores().get(d);
            readinessIn.add(new Readiness.Input(d, sc == null ? null : sc.recoveryPct(), sc == null ? null : sc.band()));
            sleepIn.add(new SleepDebt.Input(d, sc == null ? null : sc.sleepNeedMin(), in.slept().get(d)));
        }
        var readiness = Readiness.compute(readinessIn, current.intAt("signals.readiness.trendShortDays"),
                current.intAt("signals.readiness.trendLongDays"), in.firstHrv());
        var sleep = SleepDebt.compute(sleepIn, current.intAt("signals.sleep.debtWindowDays"), current.boolAt("signals.sleep.surplusPaysBack"));
        var subjective = Subjective.compute(grid, in.checkins(), current.intAt("signals.subjective.niggleStaleDays"));

        var tolIn = new ArrayList<Tolerance.Input>();
        for (int i = 0; i < grid.size(); i++) {
            tolIn.add(new Tolerance.Input(grid.get(i), inputs.get(i).runKm(), inputs.get(i).totalLoad() != null, subjective.get(i).flagged()));
        }
        var tolerance = Tolerance.compute(tolIn, Tolerance.Settings.from(current), overridesPerDay(in.overrides(), in.zone(), grid));

        var days = new ArrayList<DaySignals>(grid.size());
        for (int i = 0; i < grid.size(); i++) {
            days.add(new DaySignals(grid.get(i), load.get(i), readiness.get(i), sleep.get(i), subjective.get(i),
                    DaySignals.Sessions.of(in.sessions().getOrDefault(grid.get(i), List.of())), tolerance.get(i)));
        }
        return days;
    }

    /** The override in force on each day: the newest one set on or before that day (athlete's timezone). */
    static List<Tolerance.OverrideValue> overridesPerDay(List<SignalRepository.OverrideRow> rows, ZoneId zone, List<LocalDate> grid) {
        var out = new ArrayList<Tolerance.OverrideValue>(grid.size());
        int k = -1;
        for (LocalDate d : grid) {
            while (k + 1 < rows.size() && !rows.get(k + 1).createdAt().atZone(zone).toLocalDate().isAfter(d)) k++;
            if (k < 0 || rows.get(k).kmPerWeek() == null) out.add(null);
            else out.add(new Tolerance.OverrideValue(rows.get(k).kmPerWeek(), rows.get(k).note(),
                    rows.get(k).createdAt().atZone(zone).toLocalDate()));
        }
        return out;
    }
}
