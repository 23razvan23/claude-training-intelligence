package app.training.adaptation.engine;

import app.training.adaptation.settings.AppSettings;

/** The engine's [configurable] values, read from one settings version (catalog paths {@code engine.*}, ADR 0022). */
public record EngineSettings(int horizonDays, int swapWindowDays, double easyShare, int yellowStreakDays, double reduceShare,
                             double toleranceMargin, double acwrThreshold, double maxEasyCutShare, double maxLongCutShare,
                             double acwrOnlyEasyCutShare, double sleepDebtNights, int sleepMinKnownNights, int niggleHealthMessageDays,
                             int illnessActiveDays, int illnessReturnDays, int restoreWeeks, double restoreUnderShare,
                             double restoreStepShare, int raceWeekDays) {

    public static EngineSettings from(AppSettings s) {
        return new EngineSettings(s.intAt("engine.horizonDays"), s.intAt("engine.red.swapWindowDays"), s.doubleAt("engine.replace.easyShare"),
                s.intAt("engine.yellow.streakDays"), s.doubleAt("engine.reduce.share"), s.doubleAt("engine.volume.toleranceMargin"),
                s.doubleAt("engine.volume.acwrThreshold"), s.doubleAt("engine.volume.maxEasyCutShare"),
                s.doubleAt("engine.volume.maxLongCutShare"), s.doubleAt("engine.volume.acwrOnlyEasyCutShare"),
                s.doubleAt("engine.sleep.debtNights"), s.intAt("engine.sleep.minKnownNights"), s.intAt("engine.niggle.healthMessageDays"),
                s.intAt("engine.illness.activeDays"), s.intAt("engine.illness.returnDays"), s.intAt("engine.restore.weeks"),
                s.doubleAt("engine.restore.underShare"), s.doubleAt("engine.restore.stepShare"), s.intAt("engine.raceWeekDays"));
    }

    /** The catalog defaults, for tests. */
    public static EngineSettings defaults() {
        return new EngineSettings(7, 3, 0.6, 2, 0.25, 0.10, 1.3, 0.3, 0.2, 0.2, 1.0, 5, 3, 2, 2, 2, 0.8, 0.10, 7);
    }
}
