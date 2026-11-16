package app.training.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A calibrated model version (without its HTML report, which is fetched separately).
 *
 * @param activatedAt when the coach activated it; null if never, or if it was already ACTIVE
 *     before activation times were recorded (V13)
 * @param retiredAt when it was replaced by another ACTIVE version; null if not (or before V13)
 * @param holdoutTargets the pass marks and protocol values of the live comparison with Whoop,
 *     frozen at activation (ADR 0009); null for a version never activated since V15
 * @param contractIssue why the daily scoring could not use this version, or null if it can
 * @param olderThanActive true when the athlete's ACTIVE version was created after this one
 *     (ties broken by id); such a version can no longer be activated
 */
public record ModelVersion(
        long id,
        long athleteId,
        String version,
        String status,
        String featureVersion,
        LocalDate trainFrom,
        LocalDate trainTo,
        LocalDate testFrom,
        LocalDate testTo,
        JsonNode coefficients,
        JsonNode metrics,
        boolean passed,
        Instant createdAt,
        Instant activatedAt,
        Instant retiredAt,
        JsonNode holdoutTargets,
        ContractIssue contractIssue,
        boolean olderThanActive) {

    /** The coefficients contract version the daily scoring supports (ADR 0008, calibration/contract.schema.json). */
    public static final int SUPPORTED_SCHEMA_VERSION = 2;

    /** Why a version cannot be scored with by this app. */
    public enum ContractIssue {
        /** Built on another feature set than the one the app computes (app.metrics.feature-version). */
        FEATURE_VERSION,
        /** coefficients.schema_version is missing (made before calibration 0.6.0) or not the supported one. */
        SCHEMA_VERSION
    }

    /** The first reason the daily scoring could not use these coefficients, or null. */
    static ContractIssue contractIssue(String featureVersion, JsonNode coefficients, String appFeatureVersion) {
        if (!appFeatureVersion.equals(featureVersion)) {
            return ContractIssue.FEATURE_VERSION;
        }
        var v = coefficients == null ? null : coefficients.get("schema_version");
        if (v == null || !v.isIntegralNumber() || v.asInt() != SUPPORTED_SCHEMA_VERSION) {
            return ContractIssue.SCHEMA_VERSION;
        }
        return null;
    }
}
