package app.training.planning;

import app.training.planning.SessionTypes.Demand;
import app.training.planning.SessionTypes.Status;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The shapes the Plan API reads and writes. */
public final class PlanModels {
    private PlanModels() {}

    public record Plan(long id, long athleteId, String name, String origin, String status, LocalDate startDate,
                       LocalDate endDate, Instant createdAt) {}

    /** Create or edit a plan. {@code status} is ignored on create (a new plan is ACTIVE). */
    public record PlanRequest(String name, LocalDate startDate, LocalDate endDate, String status) {}

    /**
     * A planned session as the API shows it, and as each history row stores it ({@code before}/{@code after}).
     * {@code description} is the workout in the coach's words (structure, pace, zone, RPE); it is stored as one
     * text step in {@code planned_session.structure} (ADR 0019 D4). Distance is in km here, metres in the table.
     */
    public record Session(long id, long athleteId, Long planId, LocalDate date, String sessionType, Demand demand,
                          String title, String description, BigDecimal targetDistanceKm, Integer targetDurationMin,
                          BigDecimal estStrain, Status status, String notes, Long replacesSessionId,
                          Long changedBySuggestionId, Instant updatedAt, boolean taper) {}

    /**
     * Create or edit a session. Missing demand: the type's default. Missing status: PLANNED. Missing taper (part of
     * a taper, for the engine's guardrail, ADR 0022): not taper on create, unchanged on edit.
     */
    public record SessionRequest(Long planId, LocalDate date, String sessionType, Demand demand, String title,
                                 String description, BigDecimal targetDistanceKm, Integer targetDurationMin,
                                 BigDecimal estStrain, Status status, String notes, Boolean taper) {}

    /** One row of planned_session_revision. */
    public record Revision(long id, long sessionId, String changeKind, JsonNode before, JsonNode after,
                           Long suggestionId, String changedBy, Instant changedAt) {}
}
