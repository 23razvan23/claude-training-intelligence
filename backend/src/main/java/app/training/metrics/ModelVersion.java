package app.training.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;

/** A calibrated model version (without its HTML report, which is fetched separately). */
public record ModelVersion(
        long id,
        long athleteId,
        String version,
        String status,
        String featureVersion,
        LocalDate trainFrom,
        LocalDate trainTo,
        LocalDate testFrom,
        LocalDate testTo,
        JsonNode coefficients,
        JsonNode metrics,
        boolean passed,
        Instant createdAt) {}
