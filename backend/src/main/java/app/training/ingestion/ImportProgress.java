package app.training.ingestion;

import java.util.function.Consumer;

/**
 * Progress of one running import: a phase code plus a counter. Updates are pushed to a
 * sink at most once per interval (and on every phase change), so importers can call
 * {@link #step()} per record without flooding the database.
 */
public final class ImportProgress {
    /** For callers that do not report progress (tests, tools). */
    public static final ImportProgress NONE = new ImportProgress(null, 0);

    private final Consumer<ImportProgress> sink;
    private final long intervalNanos;
    private String phase;
    private long done;
    private Long total;
    private long lastFlush;

    public ImportProgress(Consumer<ImportProgress> sink, long intervalMillis) {
        this.sink = sink;
        this.intervalNanos = intervalMillis * 1_000_000;
    }

    /** Starts a new phase; total may be null when it is not known up front. */
    public void phase(String code, Long total) {
        this.phase = code;
        this.done = 0;
        this.total = total;
        flush(true);
    }

    public void step() {
        done++;
        flush(false);
    }

    /** Pushes the current state regardless of the interval (e.g. at the end). */
    public void flush() {
        flush(true);
    }

    private void flush(boolean force) {
        if (sink == null) return;
        long now = System.nanoTime();
        if (force || now - lastFlush >= intervalNanos) {
            lastFlush = now;
            sink.accept(this);
        }
    }

    public String phase() {
        return phase;
    }

    public long done() {
        return done;
    }

    public Long total() {
        return total;
    }
}
