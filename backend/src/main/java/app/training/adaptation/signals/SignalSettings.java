package app.training.adaptation.signals;

import app.training.adaptation.settings.AppSettings;

/** The [configurable] values the load signals use, read from one settings version (ADR 0021). */
public record SignalSettings(String dailyLoad, int acuteSpanDays, int chronicSpanDays, int fosterWindowDays, int fosterMinKnownDays,
                             int runningShortDays, int runningLongDays) {
    public static final String EDWARDS = "edwards";
    public static final String BANISTER = "banister";

    public SignalSettings {
        if (!EDWARDS.equals(dailyLoad) && !BANISTER.equals(dailyLoad))
            throw new IllegalArgumentException("signals.load.dailyLoad must be 'edwards' or 'banister', not '" + dailyLoad + "'");
        if (acuteSpanDays < 1 || chronicSpanDays < 1) throw new IllegalArgumentException("EWMA spans must be at least 1 day");
        if (fosterWindowDays < 2 || fosterMinKnownDays < 2 || fosterMinKnownDays > fosterWindowDays)
            throw new IllegalArgumentException("Foster window must be at least 2 days and hold at least minKnownDays");
        if (runningShortDays < 1 || runningLongDays < 1) throw new IllegalArgumentException("Running windows must be at least 1 day");
    }

    public static SignalSettings from(AppSettings s) {
        return new SignalSettings(s.textAt("signals.load.dailyLoad"), s.intAt("signals.load.acuteSpanDays"),
                s.intAt("signals.load.chronicSpanDays"), s.intAt("signals.foster.windowDays"), s.intAt("signals.foster.minKnownDays"),
                s.intAt("signals.running.shortWindowDays"), s.intAt("signals.running.longWindowDays"));
    }
}
