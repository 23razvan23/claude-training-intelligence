package app.training.metrics;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Where golden files are written (R-14): {@code app.golden.dir}, in Docker {@code /backups/golden} (mounted from ./backups/golden). */
@ConfigurationProperties("app.golden")
public record GoldenProperties(@DefaultValue("./backups/golden") Path dir) {}
