package app.training.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.bootstrap")
public record BootstrapProperties(String coachUsername, String coachPassword, String coachDisplayName) {}
