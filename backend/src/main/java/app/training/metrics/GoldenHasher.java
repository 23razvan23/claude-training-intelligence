package app.training.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The arithmetic of the golden-file check (R-14, ADR 0017): turns a stored row into one salted
 * hash per column (JSON columns: per leaf, e.g. {@code loads.strain_raw}) so a difference can be
 * named without ever printing or storing a value. Pure code, no database.
 */
final class GoldenHasher {
    /** Numbers are rounded to this many significant digits before hashing: a last-digit float difference is not a change. */
    static final MathContext SIGNIFICANT = new MathContext(10);
    static final String NULL = "∅";

    private GoldenHasher() {}

    /**
     * The comparable leaves of a row: {@code plain} columns by name, {@code json} columns (jsonb text) flattened to
     * {@code column.key[0].key}. Paths in {@code drop} are left out (values that depend on when the row was written).
     */
    static TreeMap<String, String> leaves(ObjectMapper json, Map<String, String> plain, Map<String, String> jsonColumns, Set<String> drop) {
        var out = new TreeMap<String, String>();
        plain.forEach((k, v) -> {
            if (!drop.contains(k)) out.put(k, v == null ? NULL : v);
        });
        jsonColumns.forEach((column, text) -> {
            if (text == null) {
                if (!drop.contains(column)) out.put(column, NULL);
                return;
            }
            try {
                flatten(column, json.readTree(text), out, drop);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Column " + column + " is not valid JSON", e);
            }
        });
        return out;
    }

    static void flatten(String path, JsonNode n, Map<String, String> out, Set<String> drop) {
        if (drop.contains(path)) return;
        if (n.isObject()) {
            if (n.isEmpty()) out.put(path + "{}", "");
            for (var name : new TreeSet<String>(iterable(n.fieldNames()))) flatten(path + "." + name, n.get(name), out, drop);
        } else if (n.isArray()) {
            if (n.isEmpty()) out.put(path + "[]", "");
            for (int i = 0; i < n.size(); i++) flatten(path + "[" + i + "]", n.get(i), out, drop);
        } else if (n.isNull()) {
            out.put(path, NULL);
        } else if (n.isNumber()) {
            out.put(path, number(n.decimalValue()));
        } else {
            out.put(path, n.asText());
        }
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        var l = new java.util.ArrayList<String>();
        it.forEachRemaining(l::add);
        return l;
    }

    static String number(BigDecimal v) {
        BigDecimal r = v.round(SIGNIFICANT);
        return r.signum() == 0 ? "0" : r.stripTrailingZeros().toPlainString();
    }

    /** One 64-bit keyed hash (16 hex characters) per leaf. */
    static TreeMap<String, String> hashes(byte[] salt, Map<String, String> leaves) {
        var out = new TreeMap<String, String>();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            leaves.forEach((path, value) -> {
                byte[] h = mac.doFinal((path + "\u0000" + value).getBytes(StandardCharsets.UTF_8));
                out.put(path, HexFormat.of().formatHex(h, 0, 8));
            });
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** SHA-256 over all column hashes: one hash for the whole row. Empty for a missing row. */
    static String rowHash(Map<String, String> hashes) {
        if (hashes.isEmpty()) return "";
        var sb = new StringBuilder();
        new TreeMap<>(hashes).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sha256(sb.toString());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fingerprint of a JSON document (unsalted: it is a model's coefficients, not a measurement). */
    static String fingerprint(ObjectMapper json, JsonNode doc) {
        var leaves = new TreeMap<String, String>();
        flatten("$", doc, leaves, Set.of());
        var sb = new StringBuilder();
        leaves.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sha256(sb.toString());
    }

    /**
     * Names (sorted) of the columns whose hash differs, appears or disappears. {@code "(row)"} when one side has no row at
     * all; empty when both agree.
     */
    static List<String> differing(Map<String, String> frozen, Map<String, String> now) {
        if (frozen.isEmpty() && now.isEmpty()) return List.of();
        if (frozen.isEmpty() || now.isEmpty()) return List.of("(row)");
        var names = new TreeSet<String>(frozen.keySet());
        names.addAll(now.keySet());
        names.removeIf(n -> java.util.Objects.equals(frozen.get(n), now.get(n)));
        return List.copyOf(names);
    }
}
