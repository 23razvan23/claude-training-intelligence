package app.training.metrics.today;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Today screen's display constants (stage 1b-2a, ADR 0014); all [configurable], rationale in
 * application.yml. None of them changes a stored score.
 *
 * @param strainTargetWindowDays the days before the shown day whose strain makes up the personal range
 * @param strainTargetMinDays fewer full-coverage strain days than this in the window: no target
 * @param greenPercentiles low and high percentile of the range on a green day
 * @param yellowPercentiles the same on a yellow day
 * @param redPercentiles the same on a red day; a percentile of 0 means "from strain 0"
 */
@ConfigurationProperties("app.today")
public record TodayProperties(
        int strainTargetWindowDays,
        int strainTargetMinDays,
        List<Double> greenPercentiles,
        List<Double> yellowPercentiles,
        List<Double> redPercentiles) {

    /** The defaults written in application.yml, for tests that build the view without Spring. */
    public static TodayProperties defaults() {
        return new TodayProperties(28, 14, List.of(50.0, 75.0), List.of(25.0, 50.0), List.of(0.0, 25.0));
    }

    List<Double> percentiles(String band) {
        return switch (band) {
            case "GREEN" -> greenPercentiles;
            case "YELLOW" -> yellowPercentiles;
            case "RED" -> redPercentiles;
            default -> null;
        };
    }
}
