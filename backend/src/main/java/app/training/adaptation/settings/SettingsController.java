package app.training.adaptation.settings;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the settings store (ADR 0021). Editing comes with the Settings screen (stage 2-5). Coach only. */
@RestController
@RequestMapping("/api/settings")
@PreAuthorize("hasRole('COACH')")
public class SettingsController {
    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    public record Entry(String path, JsonNode value, Object defaultValue, String rationale) {}

    public record Current(long version, String note, Instant createdAt, List<Entry> values) {}

    public record Version(long version, String note, Instant createdAt) {}

    /** The current version: every catalog value with its default and rationale. */
    @GetMapping
    public Current current() {
        var s = settings.current();
        var values = SettingsCatalog.all().stream()
                .map(c -> new Entry(c.path(), s.node(c.path()), c.defaultValue(), c.rationale())).toList();
        return new Current(s.id(), s.note(), s.createdAt(), values);
    }

    @GetMapping("/versions")
    public List<Version> versions() {
        return settings.history().stream().map(v -> new Version(v.id(), v.note(), v.createdAt())).toList();
    }
}
