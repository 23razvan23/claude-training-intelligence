package app.training.planning;

import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.SessionTypes.Status;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Checks one session before it is saved, and fills in what may be left out (demand from the type, status PLANNED).
 * Pure; used by manual entry and, in stage 2-1a PR 2, by every row of a spreadsheet import, so both give the same
 * messages. The limits only catch typos; they are not training advice.
 */
public final class SessionValidator {
    private SessionValidator() {}

    static final BigDecimal MAX_DISTANCE_KM = BigDecimal.valueOf(500);
    static final int MAX_DURATION_MIN = 24 * 60;
    /** The strain scale is 0–21 (ADR 0008). */
    static final BigDecimal MAX_STRAIN = BigDecimal.valueOf(21);
    static final int MAX_TITLE = 200;
    static final int MAX_TEXT = 2000;

    public record Result(SessionRequest session, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** Checks with the built-in default demand per type (settings version 1's values). */
    public static Result check(SessionRequest r) {
        return check(r, SessionTypes::defaultDemand);
    }

    /**
     * Checks one session; a session without a demand gets {@code defaultDemand} of its type, normally the current
     * settings store values ({@link DemandDefaults#current()}, ADR 0021).
     */
    public static Result check(SessionRequest r, Function<String, SessionTypes.Demand> defaultDemand) {
        List<String> problems = new ArrayList<>();
        if (r == null) return new Result(null, List.of("No session given"));
        if (r.date() == null) problems.add("Date is missing");
        if (r.sessionType() == null || r.sessionType().isBlank()) problems.add("Type is missing");
        else if (!SessionTypes.known(r.sessionType())) problems.add("Unknown type '" + r.sessionType() + "'");
        if (r.title() != null && r.title().strip().length() > MAX_TITLE) problems.add("Title is longer than " + MAX_TITLE + " characters");
        if (r.description() != null && r.description().length() > MAX_TEXT) problems.add("Description is longer than " + MAX_TEXT + " characters");
        if (r.notes() != null && r.notes().length() > MAX_TEXT) problems.add("Notes are longer than " + MAX_TEXT + " characters");
        if (r.targetDistanceKm() != null && (r.targetDistanceKm().signum() < 0 || r.targetDistanceKm().compareTo(MAX_DISTANCE_KM) > 0))
            problems.add("Distance must be between 0 and " + MAX_DISTANCE_KM + " km");
        if (r.targetDurationMin() != null && (r.targetDurationMin() < 0 || r.targetDurationMin() > MAX_DURATION_MIN))
            problems.add("Duration must be between 0 and " + MAX_DURATION_MIN + " minutes");
        if (r.estStrain() != null && (r.estStrain().signum() < 0 || r.estStrain().compareTo(MAX_STRAIN) > 0))
            problems.add("Estimated strain must be between 0 and 21");
        if (!problems.isEmpty()) return new Result(null, List.copyOf(problems));

        var completed = new SessionRequest(r.planId(), r.date(), r.sessionType(),
                r.demand() != null ? r.demand() : defaultDemand.apply(r.sessionType()),
                blankToNull(r.title()), blankToNull(r.description()), r.targetDistanceKm(), r.targetDurationMin(),
                r.estStrain(), r.status() != null ? r.status() : Status.PLANNED, blankToNull(r.notes()));
        return new Result(completed, List.of());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
