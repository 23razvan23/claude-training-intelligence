package app.training.adaptation.checkin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param maxBackdateDays how many days back a check-in may be entered [configurable]; rationale in application.yml */
@ConfigurationProperties("app.checkin")
public record CheckinProperties(int maxBackdateDays) {}
