package app.training.adaptation.checkin;

import java.time.LocalDate;

/**
 * One check-in as the form sends it. The scope is a planned session, else an activity, else the whole day
 * (ADR 0018 D6); a session or activity fixes the date, so {@code date} is only read for the whole day.
 */
public record CheckinRequest(
        LocalDate date,
        Long plannedSessionId,
        Long activityId,
        Integer rpe,
        Integer soreness,
        String niggleLocation,
        Integer niggleSeverity,
        boolean niggleResolved,
        boolean injury,
        boolean illness,
        String note) {}
