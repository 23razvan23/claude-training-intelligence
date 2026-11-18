package app.training.adaptation.checkin;

import java.time.Instant;
import java.time.LocalDate;

/** A check-in as read from subjective_current. {@code scope} is "session", "activity" or "day". */
public record CheckinView(
        long id,
        long athleteId,
        String athleteName,
        LocalDate date,
        String scope,
        Long plannedSessionId,
        Long activityId,
        Integer rpe,
        Integer soreness,
        String niggleLocation,
        Integer niggleSeverity,
        boolean niggleResolved,
        boolean injury,
        boolean illness,
        String note,
        String source,
        boolean onBehalf,
        Instant createdAt) {}
