package app.training.garmin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Garmin sync's tuning numbers (ADR 0015 D6), all [configurable], rationale in application.yml.
 *
 * @param refetchDays days before today fetched again on every sync
 * @param retryDelaysMinutes waits before the 2nd, 3rd, … attempt when Garmin is unavailable
 * @param checkMaxDays longest range "Check against export" accepts
 * @param timeoutSeconds how long the app waits for the sidecar to fetch the days
 * @param schedulerEnabled whether the background retries (and, in PR 3, the morning run) are on
 */
@ConfigurationProperties("app.garmin.sync")
public record GarminSyncProperties(int refetchDays, List<Integer> retryDelaysMinutes, int checkMaxDays, int timeoutSeconds,
                                   boolean schedulerEnabled) {}
