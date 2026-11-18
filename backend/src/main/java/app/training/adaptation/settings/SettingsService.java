package app.training.adaptation.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The settings store (stage 2-2, ADR 0018 D2, ADR 0021). Code asks {@link #current()} for every [configurable] value
 * of the signals and rules. The first call on an empty store saves the catalog defaults as version 1. When an update
 * adds a value to the catalog, the first call after it saves a new version with that value at its default (the older
 * versions are never changed), so the version a signal or suggestion names always holds every number it used.
 */
@Service
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    static final String NOTE_DEFAULTS = "Defaults (stage 2-2)";
    static final String NOTE_ADDED = "New settings added by an update, at their defaults: ";

    private final SettingsRepository repo;
    private final ObjectMapper json;

    public SettingsService(SettingsRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Transactional
    public AppSettings current() {
        var newest = repo.newest();
        if (newest.isPresent() && missing(newest.get()).isEmpty()) return newest.get();
        repo.lockForWrite();
        newest = repo.newest(); // another caller may have added it while we waited for the lock
        if (newest.isEmpty()) {
            long id = repo.insert(SettingsCatalog.defaults(json), NOTE_DEFAULTS, null);
            log.info("Settings store: saved the defaults as settings version {}", id);
            return repo.find(id).orElseThrow();
        }
        ObjectNode doc = newest.get().settings().deepCopy();
        List<String> added = SettingsCatalog.fillMissing(doc, json);
        if (added.isEmpty()) return newest.get();
        long id = repo.insert(doc, NOTE_ADDED + String.join(", ", added), null);
        log.info("Settings store: saved settings version {} with {} new value(s) at their defaults", id, added.size());
        return repo.find(id).orElseThrow();
    }

    public List<AppSettings> history() {
        return repo.all();
    }

    private List<String> missing(AppSettings s) {
        if (!(s.settings() instanceof ObjectNode o)) return List.of("(not a JSON object)");
        return SettingsCatalog.fillMissing(o.deepCopy(), json);
    }
}
