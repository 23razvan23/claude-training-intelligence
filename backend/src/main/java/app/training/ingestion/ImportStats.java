package app.training.ingestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counters for one import batch, reported back to the user. Not thread-safe; one per batch. */
public final class ImportStats {
    private static final int MAX_WARNINGS = 200;

    public record Counter(long inserted, long duplicates, long skipped) {}

    private final Map<String, long[]> counters = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private long droppedWarnings;

    private long[] c(String table) {
        return counters.computeIfAbsent(table, k -> new long[3]);
    }

    public void inserted(String table, long n) {
        c(table)[0] += n;
    }

    public void duplicates(String table, long n) {
        c(table)[1] += n;
    }

    public void skipped(String table, long n) {
        c(table)[2] += n;
    }

    public void warn(String message) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(message);
        else droppedWarnings++;
    }

    public Map<String, Counter> counters() {
        var out = new LinkedHashMap<String, Counter>();
        counters.forEach((k, v) -> out.put(k, new Counter(v[0], v[1], v[2])));
        return out;
    }

    public long insertedTotal() {
        return counters.values().stream().mapToLong(v -> v[0]).sum();
    }

    public List<String> warnings() {
        var out = new ArrayList<>(warnings);
        if (droppedWarnings > 0) out.add("... and " + droppedWarnings + " more warnings");
        return out;
    }
}
