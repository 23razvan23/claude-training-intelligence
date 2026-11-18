package app.training.adaptation.settings;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.planning.SessionTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class SettingsCatalogTest {
    final ObjectMapper json = new ObjectMapper();

    @Test
    void everySettingHasAUniquePathADefaultAndARationale() {
        var paths = new HashSet<String>();
        for (var s : SettingsCatalog.all()) {
            assertThat(paths.add(s.path())).as("duplicate " + s.path()).isTrue();
            assertThat(s.defaultValue()).as(s.path()).isNotNull();
            assertThat(s.rationale()).as(s.path()).isNotBlank();
        }
    }

    @Test
    void theDefaultsDocumentHoldsEveryValue() {
        var doc = SettingsCatalog.defaults(json);
        assertThat(SettingsCatalog.at(doc, "signals.load.dailyLoad").asText()).isEqualTo("edwards");
        assertThat(SettingsCatalog.at(doc, "signals.load.chronicSpanDays").asInt()).isEqualTo(28);
        assertThat(SettingsCatalog.at(doc, "signals.tolerance.decayPerWeek").asDouble()).isEqualTo(0.10);
        for (var t : SessionTypes.all())
            assertThat(SettingsCatalog.at(doc, "planning.defaultDemand." + t.code()).asText()).isEqualTo(t.defaultDemand().name());
        assertThat(SettingsCatalog.fillMissing(doc, json)).isEmpty();
    }

    @Test
    void fillMissingAddsOnlyWhatIsMissingAndKeepsStoredAndUnknownValues() throws Exception {
        var doc = (ObjectNode) json.readTree("{\"signals\":{\"load\":{\"acuteSpanDays\":5}},\"legacy\":1}");
        var added = SettingsCatalog.fillMissing(doc, json);
        assertThat(added).contains("signals.load.chronicSpanDays").doesNotContain("signals.load.acuteSpanDays");
        assertThat(SettingsCatalog.at(doc, "signals.load.acuteSpanDays").asInt()).isEqualTo(5);
        assertThat(doc.get("legacy").asInt()).isEqualTo(1);
    }

    @Test
    void atReturnsNullForMissingPaths() throws Exception {
        var doc = json.readTree("{\"a\":{\"b\":1}}");
        assertThat(SettingsCatalog.at(doc, "a.b").asInt()).isEqualTo(1);
        assertThat(SettingsCatalog.at(doc, "a.c")).isNull();
        assertThat(SettingsCatalog.at(doc, "a.b.c")).isNull();
    }
}
