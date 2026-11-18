package app.training.adaptation.settings;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * One saved settings version (ADR 0018 D2, ADR 0021): its id and the whole document. Readers ask for a value by its
 * dotted path; a missing value is a bug (the store always fills in new catalog values), so it fails loudly.
 */
public record AppSettings(long id, JsonNode settings, String note, Instant createdAt) {

    public JsonNode node(String path) {
        JsonNode n = SettingsCatalog.at(settings, path);
        if (n == null) throw new IllegalStateException("Setting " + path + " is missing from settings version " + id);
        return n;
    }

    public int intAt(String path) {
        JsonNode n = node(path);
        if (!n.canConvertToInt()) throw new IllegalStateException("Setting " + path + " is not a whole number");
        return n.asInt();
    }

    public double doubleAt(String path) {
        JsonNode n = node(path);
        if (!n.isNumber()) throw new IllegalStateException("Setting " + path + " is not a number");
        return n.asDouble();
    }

    public boolean boolAt(String path) {
        JsonNode n = node(path);
        if (!n.isBoolean()) throw new IllegalStateException("Setting " + path + " is not true/false");
        return n.asBoolean();
    }

    public String textAt(String path) {
        JsonNode n = node(path);
        if (!n.isTextual()) throw new IllegalStateException("Setting " + path + " is not text");
        return n.asText();
    }
}
