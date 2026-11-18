package app.training.adaptation.checkin;

/**
 * How Garmin's effort fields become check-in answers (ADR 0020 amendment, confirmed by the coach on the
 * real value ranges): {@code workoutRpe} is a 0-100 scale in steps of 10, so the check-in RPE (1-10) is
 * the value divided by 10. {@code workoutFeel} has no check-in counterpart and is never seeded.
 */
public final class GarminEffort {
    /** Highest RPE a check-in accepts. */
    static final int MAX_RPE = 10;

    private GarminEffort() {}

    /**
     * @param workoutRpe Garmin's value, or null
     * @param divisor    the scale factor [configurable: app.checkin.garmin-rpe-divisor]; 10 because Garmin's scale
     *                   is 0-100 in steps of 10 and all 143 values on the coach's export were multiples of 10
     * @return the check-in RPE, or null when there is nothing usable (empty, 0 or negative = "not set")
     */
    public static Integer rpe(Integer workoutRpe, int divisor) {
        if (workoutRpe == null || workoutRpe <= 0 || divisor <= 0) return null;
        long rounded = Math.round((double) workoutRpe / divisor);   // half up; a value off the 10-step is rounded, not refused
        return (int) Math.max(1, Math.min(MAX_RPE, rounded));
    }
}
