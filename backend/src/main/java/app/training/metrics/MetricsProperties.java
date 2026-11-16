package app.training.metrics;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;

/** Module 1 constants; all [configurable] (see application.yml for the rationale of each). */
@ConfigurationProperties("app.metrics")
public record MetricsProperties(
        String featureVersion,
        double banisterMaleK1,
        double banisterMaleK2,
        double banisterFemaleK1,
        double banisterFemaleK2,
        List<Double> edwardsZoneFloors,
        List<Double> hrrZoneFloors,
        int maxSampleGapSeconds,
        int monitoringMaxGapSeconds,
        int baselineWindowDays,
        int baselineMinDays,
        int holdoutMinRecoveryDays,
        double holdoutConfidence,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate holdoutWhoopEnd,
        int holdoutRollingDays) {}
