package app.training.metrics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * The golden file (R-14, ADR 0017): hashes of a frozen window plus what they were built from.
 * It holds no measured value, but small-range columns could be guessed from their hashes, so it
 * is as private as a backup: it lives in the git-ignored ./backups/golden and is never shared.
 *
 * @param mode "full" (replay from raw data) or "scores" (replay only the scoring, from stored features)
 * @param batchWatermark the highest import batch id at freeze time; later batches are ignored by the replay
 * @param raw per raw table: row count and content checksum at freeze time (empty in "scores" mode)
 */
record GoldenFile(
        int schema, String mode, Instant frozenAt, long athleteId, LocalDate from, LocalDate to, String featureVersion,
        String modelVersion, String modelFingerprint, String appVersion, String commit, String salt, Profile profile,
        long batchWatermark, Map<String, Raw> raw, Map<String, Day> days) {

    record Profile(Integer hrMax, Integer restingHr, String sex, String timezone) {}

    record Raw(long rows, String checksum) {}

    /** Hash of the whole row and of each column, for day_features and for the ACTIVE model's derived_day_metrics. */
    record Day(String features, String scores, Map<String, String> featureColumns, Map<String, String> scoreColumns) {}
}
