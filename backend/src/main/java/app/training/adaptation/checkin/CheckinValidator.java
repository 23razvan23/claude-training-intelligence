package app.training.adaptation.checkin;

import app.training.common.ApiException;
import java.time.LocalDate;
import java.util.List;

/** The rules for one check-in. Pure: no database, no clock (the caller passes today). */
public final class CheckinValidator {
    /** Body locations the form offers. Free text belongs in the note. */
    public static final List<String> LOCATIONS = List.of(
            "foot", "ankle", "calf", "shin", "knee", "hamstring", "quad", "hip", "glute", "back", "other");
    public static final int NOTE_MAX = 500;

    private CheckinValidator() {}

    /** Returns the request with the note trimmed (blank becomes null); throws a 400 for anything wrong. */
    public static CheckinRequest validate(CheckinRequest r, LocalDate logDate, LocalDate today, int maxBackdateDays) {
        if (r.plannedSessionId() != null && r.activityId() != null) {
            throw ApiException.badRequest("Choose either a planned session or an activity, not both");
        }
        if (logDate.isAfter(today)) throw ApiException.badRequest("A check-in cannot be for a future day");
        if (logDate.isBefore(today.minusDays(maxBackdateDays))) {
            throw ApiException.badRequest("A check-in can be at most " + maxBackdateDays + " days old");
        }
        range("RPE", r.rpe(), 1, 10);
        range("Soreness", r.soreness(), 1, 5);
        range("Niggle severity", r.niggleSeverity(), 1, 5);
        String location = r.niggleLocation() == null || r.niggleLocation().isBlank() ? null : r.niggleLocation().trim();
        if (location != null && !LOCATIONS.contains(location)) throw ApiException.badRequest("Unknown body location");
        if (location == null && (r.niggleSeverity() != null || r.niggleResolved())) {
            throw ApiException.badRequest("A niggle needs a body location");
        }
        if (r.niggleResolved() && r.niggleSeverity() != null) {
            throw ApiException.badRequest("A niggle that is gone has no severity");
        }
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        if (note != null && note.length() > NOTE_MAX) throw ApiException.badRequest("The note is too long");
        boolean answered = r.rpe() != null || r.soreness() != null || location != null || r.injury() || r.illness()
                || note != null;
        if (!answered) throw ApiException.badRequest("Answer at least one question");
        return new CheckinRequest(r.date(), r.plannedSessionId(), r.activityId(), r.rpe(), r.soreness(), location,
                r.niggleSeverity(), r.niggleResolved(), r.injury(), r.illness(), note);
    }

    private static void range(String what, Integer v, int lo, int hi) {
        if (v != null && (v < lo || v > hi)) throw ApiException.badRequest(what + " must be " + lo + " to " + hi);
    }
}
