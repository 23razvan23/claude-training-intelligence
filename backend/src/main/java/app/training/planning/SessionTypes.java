package app.training.planning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The fixed list of session types (SPEC.md session library; same codes as {@code session_template.session_type})
 * and the demand a new session of each type starts with. The coach can change the demand on any session.
 *
 * <p>Default demand per type [configurable] (ADR 0019 D3): long runs, threshold, VO2max, race-pace work and races
 * start as KEY because they are the sessions a week is built around; hills as SUPPORTING; everything else as EASY.
 * A coaching convention, not a measured fact. Since stage 2-2 these are only the starting values of the settings
 * store ({@code planning.defaultDemand.*}, ADR 0021); the app reads the current values through {@link DemandDefaults}.
 */
public final class SessionTypes {
    private SessionTypes() {}

    public enum Demand { KEY, SUPPORTING, EASY }

    public enum Status { PLANNED, DONE, SKIPPED, REPLACED }

    public record SessionType(String code, Demand defaultDemand) {}

    private static final Map<String, Demand> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("easy", Demand.EASY);
        DEFAULTS.put("recovery", Demand.EASY);
        DEFAULTS.put("long", Demand.KEY);
        DEFAULTS.put("long_fast_finish", Demand.KEY);
        DEFAULTS.put("threshold", Demand.KEY);
        DEFAULTS.put("vo2max", Demand.KEY);
        DEFAULTS.put("race_pace", Demand.KEY);
        DEFAULTS.put("hills", Demand.SUPPORTING);
        DEFAULTS.put("strides", Demand.EASY);
        DEFAULTS.put("race", Demand.KEY);
        DEFAULTS.put("cross", Demand.EASY);
        DEFAULTS.put("rest", Demand.EASY);
    }

    public static List<SessionType> all() {
        return DEFAULTS.entrySet().stream().map(e -> new SessionType(e.getKey(), e.getValue())).toList();
    }

    /** The types with the demand a new session starts with, as given by {@code demandFor} (the settings store). */
    public static List<SessionType> all(Function<String, Demand> demandFor) {
        return DEFAULTS.keySet().stream().map(code -> new SessionType(code, demandFor.apply(code))).toList();
    }

    public static boolean known(String code) {
        return code != null && DEFAULTS.containsKey(code);
    }

    /** The built-in starting value (settings version 1); code reads the current value through {@link DemandDefaults}. */
    public static Demand defaultDemand(String code) {
        return DEFAULTS.getOrDefault(code, Demand.EASY);
    }
}
