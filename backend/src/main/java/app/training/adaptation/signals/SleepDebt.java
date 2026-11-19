package app.training.adaptation.signals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sleep debt over the last N nights including the day (SPEC.md Module 3; ADR 0021 D10) [configurable, 7]: the
 * shortfall of sleep against Module 1's sleep need. With {@code surplusPaysBack} (coach, 2026-11-18) the nights are
 * walked oldest first and a longer night pays back an earlier shortfall; the debt never goes below 0, so a long night
 * before a short one does not pre-pay it. Without it, only shortfalls add up. A night without a need or without a
 * recorded sleep is skipped and counted as unknown, never as 0 hours.
 */
public final class SleepDebt {
    private SleepDebt() {}

    public record Input(LocalDate date, Integer needMin, Integer sleptMin) {}

    /** {@code nightsOfNeed} = debt ÷ the mean need of the known nights (the spec's "more than one night's need"). */
    public record Day(int windowDays, int knownNights, Integer debtMin, Double nightsOfNeed) {}

    public static List<Day> compute(List<Input> days, int windowDays, boolean surplusPaysBack) {
        var out = new ArrayList<Day>(days.size());
        for (int i = 0; i < days.size(); i++) {
            double debt = 0, needSum = 0;
            int known = 0;
            for (int j = Math.max(0, i - windowDays + 1); j <= i; j++) {
                var d = days.get(j);
                if (d.needMin() == null || d.sleptMin() == null || d.needMin() <= 0) continue;
                known++;
                needSum += d.needMin();
                double gap = d.needMin() - d.sleptMin();
                debt = surplusPaysBack ? Math.max(0, debt + gap) : debt + Math.max(0, gap);
            }
            out.add(known == 0 ? new Day(windowDays, 0, null, null)
                    : new Day(windowDays, known, (int) Math.round(debt), LoadSignals.round(debt / (needSum / known), 2)));
        }
        return out;
    }
}
