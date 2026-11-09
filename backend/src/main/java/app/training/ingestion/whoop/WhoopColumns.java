package app.training.ingestion.whoop;

import app.training.ingestion.CsvTable;
import java.util.List;

/**
 * Every Whoop export column the importer reads, with the header spellings it accepts.
 * THIS IS THE ONE PLACE TO FIX if a real export uses different headers.
 * Assumed layout: docs/adr/0004-export-format-assumptions.md.
 */
public enum WhoopColumns implements CsvTable.Column {
    // shared by every Whoop CSV
    CYCLE_START("Cycle start time"),
    CYCLE_END("Cycle end time"),
    CYCLE_TIMEZONE("Cycle timezone"),

    // physiological_cycles.csv
    RECOVERY("Recovery score %", "Recovery score"),
    RESTING_HR("Resting heart rate (bpm)", "Resting heart rate"),
    HRV("Heart rate variability (ms)", "HRV (ms)"),
    SKIN_TEMP("Skin temp (celsius)", "Skin temperature (celsius)"),
    SPO2("Blood oxygen %", "SpO2 %"),
    DAY_STRAIN("Day Strain", "Day strain"),
    ENERGY("Energy burned (cal)", "Energy burned (kcal)"),

    // physiological_cycles.csv and sleeps.csv
    SLEEP_ONSET("Sleep onset"),
    WAKE_ONSET("Wake onset"),
    SLEEP_PERFORMANCE("Sleep performance %", "Sleep performance"),
    RESPIRATORY_RATE("Respiratory rate (rpm)", "Respiratory rate"),
    ASLEEP_MIN("Asleep duration (min)"),
    LIGHT_MIN("Light sleep duration (min)"),
    DEEP_MIN("Deep (SWS) duration (min)", "Deep sleep duration (min)"),
    REM_MIN("REM duration (min)", "REM sleep duration (min)"),
    AWAKE_MIN("Awake duration (min)"),
    SLEEP_NEED_MIN("Sleep need (min)"),
    SLEEP_DEBT_MIN("Sleep debt (min)"),
    NAP("Nap"),

    // workouts.csv
    WORKOUT_START("Workout start time"),
    WORKOUT_END("Workout end time"),
    WORKOUT_DURATION_MIN("Duration (min)"),
    ACTIVITY_NAME("Activity name", "Sport"),
    ACTIVITY_STRAIN("Activity Strain", "Workout strain"),
    MAX_HR("Max HR (bpm)"),
    AVG_HR("Average HR (bpm)"),
    DISTANCE_M("Distance (meters)", "Distance (m)"),
    ALTITUDE_GAIN_M("Altitude gain (meters)", "Altitude gain (m)"),

    // journal_entries.csv
    QUESTION("Question text"),
    ANSWERED_YES("Answered yes"),
    NOTES("Notes");

    private final List<String> aliases;

    WhoopColumns(String... aliases) {
        this.aliases = List.of(aliases);
    }

    @Override
    public List<String> aliases() {
        return aliases;
    }

    /** Which Whoop file a CSV is, recognised from its headers rather than its name. */
    public enum FileType {
        CYCLES, SLEEPS, WORKOUTS, JOURNAL, UNKNOWN;

        public static FileType detect(CsvTable t) {
            if (!t.has(CYCLE_START)) return UNKNOWN;
            if (t.has(QUESTION)) return JOURNAL;
            if (t.has(WORKOUT_START)) return WORKOUTS;
            if (t.has(RECOVERY) || t.has(DAY_STRAIN)) return CYCLES;
            if (t.has(SLEEP_ONSET)) return SLEEPS;
            return UNKNOWN;
        }
    }
}
