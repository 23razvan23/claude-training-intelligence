package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The scorer reads only contracts it understands (ADR 0008, calibration/contract.schema.json). */
class ScoringContractTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode fixture(String name) throws IOException {
        return (ObjectNode) JSON.readTree(DailyScorerParityTest.resource(name, "expected.json")).get("coefficients").deepCopy();
    }

    @Test
    void readsBothStrainForms() throws IOException {
        var load = ScoringContract.parse(fixture("load"));
        assertThat(load.strain().form()).isEqualTo(ScoringContract.Form.LOAD);
        assertThat(load.strain().loadColumn()).isEqualTo("day_load_banister");
        assertThat(load.strain().usesBackground()).isTrue();
        assertThat(load.featureVersion()).isEqualTo("f3");
        assertThat(load.hrMax()).isEqualTo(190);
        assertThat(load.hrRest()).isEqualTo(50);
        assertThat(load.trainingRanges().get("strain")).containsKeys("day_load_banister", "background_load");

        var zones = ScoringContract.parse(fixture("zones"));
        assertThat(zones.strain().form()).isEqualTo(ScoringContract.Form.ZONES);
        assertThat(zones.strain().zoneWeights()).hasSize(6);
    }

    @Test
    void refusesAnyOtherSchemaVersion() throws IOException {
        for (int v : new int[] {1, 3}) {
            var c = fixture("load");
            c.put("schema_version", v);
            assertThatThrownBy(() -> ScoringContract.parse(c)).isInstanceOf(ScoringContract.Invalid.class)
                    .hasMessageContaining("schema_version");
        }
        var missing = fixture("load");
        missing.remove("schema_version");
        assertThatThrownBy(() -> ScoringContract.parse(missing)).isInstanceOf(ScoringContract.Invalid.class)
                .hasMessageContaining("schema_version");
        var text = fixture("load");
        text.put("schema_version", "2");
        assertThatThrownBy(() -> ScoringContract.parse(text)).isInstanceOf(ScoringContract.Invalid.class);
    }

    @Test
    void refusesWhatItCannotScore() throws IOException {
        var form = fixture("load");
        ((ObjectNode) form.get("strain")).put("form", "curve");
        assertThatThrownBy(() -> ScoringContract.parse(form)).isInstanceOf(ScoringContract.Invalid.class).hasMessageContaining("form");

        var load = fixture("load");
        ((ObjectNode) load.get("strain")).put("load", "day_load_mystery");
        assertThatThrownBy(() -> ScoringContract.parse(load)).isInstanceOf(ScoringContract.Invalid.class);

        var term = fixture("load");
        ((ObjectNode) term.get("recovery").get("terms").get(0)).put("feature", "skin_temp_dev");
        assertThatThrownBy(() -> ScoringContract.parse(term)).isInstanceOf(ScoringContract.Invalid.class)
                .hasMessageContaining("skin_temp_dev");

        var scale = fixture("load");
        ((ObjectNode) scale.get("recovery").get("terms").get(0)).put("scale", 0);
        assertThatThrownBy(() -> ScoringContract.parse(scale)).isInstanceOf(ScoringContract.Invalid.class);

        var zones = fixture("zones");
        ((ArrayNode) zones.get("strain").get("zone_weights")).remove(0);
        assertThatThrownBy(() -> ScoringContract.parse(zones)).isInstanceOf(ScoringContract.Invalid.class);

        var noSleep = fixture("load");
        noSleep.remove("sleep");
        assertThatThrownBy(() -> ScoringContract.parse(noSleep)).isInstanceOf(ScoringContract.Invalid.class).hasMessageContaining("sleep");

        JsonNode notAnObject = JSON.readTree("{\"schema_version\": 2}");
        assertThatThrownBy(() -> ScoringContract.parse(notAnObject)).isInstanceOf(ScoringContract.Invalid.class);
    }
}
