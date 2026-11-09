package app.training.athletes;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record Athlete(
        Long id,
        String name,
        String sex,
        Integer birthYear,
        Integer hrMax,
        Integer restingHr,
        Integer lthr,
        Integer thresholdPaceSPerKm,
        JsonNode hrZones,
        JsonNode paceZones,
        String timezone,
        String injuryNotes,
        Instant createdAt,
        Instant updatedAt) {}
