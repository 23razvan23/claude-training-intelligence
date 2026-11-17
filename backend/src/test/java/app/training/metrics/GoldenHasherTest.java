package app.training.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class GoldenHasherTest {
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final byte[] SALT = "0123456789abcdef".getBytes();

    private static Map<String, String> leaves(Map<String, String> plain, Map<String, String> json) {
        return GoldenHasher.leaves(JSON, plain, json, Set.of("data_quality.stale"));
    }

    @Test
    void sameValuesGiveSameHashesAndAnyChangeIsNamed() {
        var plain = new TreeMap<>(Map.of("rhr", "48", "ln_rmssd", "3.9100"));
        var json = Map.of("loads", "{\"strain\": 7.1481, \"zones\": [1, 2.50]}");
        var a = GoldenHasher.hashes(SALT, leaves(plain, json));
        var b = GoldenHasher.hashes(SALT, leaves(plain, json));
        assertThat(GoldenHasher.differing(a, b)).isEmpty();
        assertThat(GoldenHasher.rowHash(a)).isEqualTo(GoldenHasher.rowHash(b));

        var changed = GoldenHasher.hashes(SALT, leaves(Map.of("rhr", "49", "ln_rmssd", "3.9100"),
                Map.of("loads", "{\"strain\": 7.1482, \"zones\": [1, 2.5]}")));
        assertThat(GoldenHasher.differing(a, changed)).containsExactly("loads.strain", "rhr");
        assertThat(GoldenHasher.rowHash(a)).isNotEqualTo(GoldenHasher.rowHash(changed));
    }

    @Test
    void jsonKeyOrderSpacingAndTrailingZerosDoNotMatterButRealDigitsDo() {
        var a = GoldenHasher.hashes(SALT, leaves(Map.of(), Map.of("loads", "{\"a\": 1.50, \"b\": 2}")));
        var b = GoldenHasher.hashes(SALT, leaves(Map.of(), Map.of("loads", "{\"b\":2.0,\"a\":1.5}")));
        assertThat(GoldenHasher.differing(a, b)).isEmpty();
        // noise beyond 10 significant digits (a 1-ulp float difference) is ignored, a real change is not
        var noise = GoldenHasher.hashes(SALT, leaves(Map.of(), Map.of("loads", "{\"a\": 1.50000000000001, \"b\": 2}")));
        assertThat(GoldenHasher.differing(a, noise)).isEmpty();
        var real = GoldenHasher.hashes(SALT, leaves(Map.of(), Map.of("loads", "{\"a\": 1.5001, \"b\": 2}")));
        assertThat(GoldenHasher.differing(a, real)).containsExactly("loads.a");
    }

    @Test
    void volatileKeysAreLeftOut() {
        var a = GoldenHasher.hashes(SALT, leaves(Map.of(), Map.of("data_quality", "{\"stale\": true, \"low_coverage\": false}")));
        var b = GoldenHasher.hashes(SALT, leaves(Map.of(), Map.of("data_quality", "{\"stale\": false, \"low_coverage\": false}")));
        assertThat(GoldenHasher.differing(a, b)).isEmpty();
    }

    @Test
    void aColumnThatAppearsOrDisappearsIsNamedAndAMissingRowIsReported() {
        var a = GoldenHasher.hashes(SALT, leaves(Map.of("x", "1"), Map.of()));
        var b = GoldenHasher.hashes(SALT, leaves(Map.of("x", "1", "y", "2"), Map.of()));
        assertThat(GoldenHasher.differing(a, b)).containsExactly("y");
        assertThat(GoldenHasher.differing(Map.of(), b)).containsExactly("(row)");
        assertThat(GoldenHasher.differing(a, Map.of())).containsExactly("(row)");
        assertThat(GoldenHasher.differing(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void nullIsNotTheSameAsAnEmptyOrZeroValueAndHashesDependOnTheSalt() {
        var nul = new java.util.HashMap<String, String>();
        nul.put("x", null);
        var a = GoldenHasher.hashes(SALT, leaves(nul, Map.of()));
        var zero = GoldenHasher.hashes(SALT, leaves(Map.of("x", "0"), Map.of()));
        assertThat(GoldenHasher.differing(a, zero)).containsExactly("x");
        var other = GoldenHasher.hashes("fedcba9876543210".getBytes(), leaves(nul, Map.of()));
        assertThat(other).isNotEqualTo(a);
    }

    @Test
    void hashesNeverContainTheValues() {
        var h = GoldenHasher.hashes(SALT, leaves(Map.of("rhr", "48271"), Map.of("loads", "{\"strain\": 7.1481}")));
        assertThat(h.values()).allSatisfy(v -> assertThat(v).matches("[0-9a-f]{16}").doesNotContain("48271").doesNotContain("71481"));
        assertThat(List.copyOf(h.keySet())).containsExactly("loads.strain", "rhr");
    }
}
