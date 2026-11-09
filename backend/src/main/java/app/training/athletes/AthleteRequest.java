package app.training.athletes;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create / replace payload for an athlete profile. */
public record AthleteRequest(
        @NotBlank @Size(max = 120) String name,
        @Pattern(regexp = "[MF]") String sex,
        @Min(1900) @Max(2100) Integer birthYear,
        @Min(100) @Max(250) Integer hrMax,
        @Min(25) @Max(120) Integer restingHr,
        @Min(80) @Max(230) Integer lthr,
        @Min(120) @Max(900) Integer thresholdPaceSPerKm,
        JsonNode hrZones,
        JsonNode paceZones,
        @Size(max = 64) String timezone,
        @Size(max = 4000) String injuryNotes) {}
