package app.training.metrics.trends;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Trends screen's display constants (stage 1b-2b, ADR 0015); all [configurable], rationale in
 * application.yml. None of them changes a stored score.
 *
 * @param historyDays how far back the screen can look (its longest range)
 * @param warmupDays extra days before that, so the load averages are already settled on the first visible day
 */
@ConfigurationProperties("app.trends")
public record TrendsProperties(int historyDays, int warmupDays) {
    public static TrendsProperties defaults() {
        return new TrendsProperties(365, 90);
    }
}
