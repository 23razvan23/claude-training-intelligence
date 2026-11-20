package app.training.adaptation.backtest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * What one backtest run replays and how (ADR 0023). Stored with the run, so a report always says how it was made.
 * Every number is [configurable] (a command-line option) with the rationale next to its default.
 *
 * @param from           first replayed morning
 * @param to             last replayed morning (default yesterday: today's morning belongs to the live engine)
 * @param morningCutoff  rows with a typing time (check-ins, tolerance overrides) count for a morning only if saved
 *                       before this local time [09:00: after the 07:00 sync and a usual morning check-in] (Q6)
 * @param races          dates of activities that were races (the coach names them; Garmin's export does not say)
 * @param hardWeeks      Mondays of the weeks the coach remembers as hard (for the report)
 * @param settingsVersion the settings version to use; null = the current one
 */
public record BacktestParams(LocalDate from, LocalDate to, LocalTime morningCutoff, List<LocalDate> races, List<LocalDate> hardWeeks,
                             Classification classification, Long settingsVersion) {

    /** [configurable] 12 months (Q7). */
    public static final int DEFAULT_DAYS = 365;
    /** [configurable] Q6. */
    public static final LocalTime DEFAULT_CUTOFF = LocalTime.of(9, 0);

    public BacktestParams {
        races = races == null ? List.of() : List.copyOf(races);
        hardWeeks = hardWeeks == null ? List.of() : hardWeeks.stream().map(d -> d.with(DayOfWeek.MONDAY)).distinct().sorted().toList();
        if (morningCutoff == null) morningCutoff = DEFAULT_CUTOFF;
        if (classification == null) classification = Classification.DEFAULTS;
        if (from == null || to == null) throw new IllegalArgumentException("from and to are required");
        if (to.isBefore(from)) throw new IllegalArgumentException("to is before from");
    }

    /** The last 12 months up to yesterday. */
    public static BacktestParams lastYear(LocalDate today) {
        LocalDate to = today.minusDays(1);
        return new BacktestParams(to.minusDays(DEFAULT_DAYS - 1), to, DEFAULT_CUTOFF, List.of(), List.of(), Classification.DEFAULTS, null);
    }

    /**
     * How a Garmin activity becomes a session of the stand-in plan (Q1, coach 2026-11-20: "go with your suggestions").
     * Heart-rate zones are heart-rate-reserve bands as the features compute them (zone 4 = 80–90 %, zone 5 = 90 %+).
     * All heuristics, not facts.
     *
     * @param vo2maxZone5Min      [8 min] at least this long above 90 % HRR = a VO2max session: typical interval work
     *                            spends 8–20 min there; easy running almost none
     * @param thresholdZone4PlusMin [15 min] at least this long above 80 % HRR = a threshold session: the shortest
     *                            common tempo block
     * @param longMin             [90 min] a run this long is a long run
     * @param longKm              [18 km] … or this far (on the coach's data only 11 runs in 12 months qualify)
     * @param fastFinishZone4PlusMin [10 min] a long run with this much above 80 % HRR is a long run with a fast finish
     * @param crossSports         normalised sports that count as cross-training
     * @param crossSourceSports   Garmin's own names that count as cross-training even though their sport is "other"
     */
    public record Classification(double vo2maxZone5Min, double thresholdZone4PlusMin, int longMin, double longKm,
                                 double fastFinishZone4PlusMin, List<String> crossSports, List<String> crossSourceSports) {
        public static final Classification DEFAULTS = new Classification(8, 15, 90, 18, 10,
                List.of("cycling", "strength", "swimming", "hiking"), List.of("hiit", "squash"));
    }
}
