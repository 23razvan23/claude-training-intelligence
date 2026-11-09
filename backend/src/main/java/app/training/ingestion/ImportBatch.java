package app.training.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ImportBatch(
        long id,
        long athleteId,
        ImportKind kind,
        String filename,
        String status,
        JsonNode stats,
        JsonNode warnings,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        JsonNode progress) {}
