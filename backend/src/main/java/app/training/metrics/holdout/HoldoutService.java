package app.training.metrics.holdout;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.metrics.MetricsProperties;
import app.training.metrics.MetricsRepository;
import app.training.metrics.ModelVersion;
import app.training.metrics.ScoringContract;
import app.training.metrics.ScoringRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The live comparison with Whoop (ADR 0009) for one model version: gathers that version's
 * stored scores, Whoop's finished cycles and the frozen pass marks, and hands them to
 * {@link HoldoutEvaluator}. Read-only: nothing here can edit a mark or leave a day out.
 */
@Service
public class HoldoutService {
    private final AthleteRepository athletes;
    private final MetricsRepository models;
    private final ScoringRepository scores;
    private final HoldoutRepository repo;
    private final MetricsProperties props;
    private final Clock clock;

    public HoldoutService(AthleteRepository athletes, MetricsRepository models, ScoringRepository scores, HoldoutRepository repo,
                          MetricsProperties props, Clock clock) {
        this.athletes = athletes;
        this.models = models;
        this.scores = scores;
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /** A version that has (or had) a live comparison: every version activated since frozen marks exist. */
    public record VersionSummary(String version, String status, Instant activatedAt, Instant retiredAt, LocalDate windowFrom) {}

    public List<VersionSummary> versions(long athleteId) {
        return models.models(athleteId).stream()
                .filter(m -> m.holdoutTargets() != null && m.holdoutTargets().isObject())
                .map(m -> new VersionSummary(m.version(), m.status(), m.activatedAt(), m.retiredAt(),
                        m.holdoutTargets().hasNonNull("window_from") ? LocalDate.parse(m.holdoutTargets().get("window_from").asText()) : null))
                .toList();
    }

    /**
     * The comparison for the given version, or the ACTIVE one when {@code version} is null.
     * 404 when there is no such version; 409 when it has no frozen pass marks.
     */
    public HoldoutEvaluator.Report report(long athleteId, String version) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        ModelVersion m = version == null
                ? models.active(athleteId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "No ACTIVE model: calibrate and activate a model version first."))
                : models.models(athleteId).stream().filter(x -> x.version().equals(version)).findFirst()
                        .orElseThrow(() -> ApiException.notFound("Model version"));
        HoldoutEvaluator.Marks marks;
        try {
            marks = HoldoutEvaluator.Marks.of(m.holdoutTargets());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Model " + m.version() + " has no frozen pass marks for the live comparison "
                    + "with Whoop (" + e.getMessage() + "). Only versions activated with frozen marks have one (ADR 0009).");
        }
        ScoringContract contract;
        try {
            contract = ScoringContract.parse(m.coefficients());
        } catch (ScoringContract.Invalid e) {
            throw new ApiException(HttpStatus.CONFLICT, "Model " + m.version() + " can't be read: " + e.getMessage());
        }

        ZoneId zone = ZoneId.of(athlete.timezone());
        boolean retired = "RETIRED".equals(m.status());
        Instant until = retired ? m.retiredAt() : null;
        var ours = scores.days(athleteId, m.version(), marks.windowFrom(), null).stream().map(d -> ourDay(d, contract)).toList();
        var whoop = repo.whoop(athleteId, marks.windowFrom().minusDays(1), until);
        var runs = repo.recomputeRuns(athleteId, marks.windowFrom().atStartOfDay(zone).toInstant(), until);

        return HoldoutEvaluator.evaluate(new HoldoutEvaluator.Input(
                m.version(), m.status(), m.activatedAt(), m.retiredAt(), m.holdoutTargets(), marks,
                contract.greenMin(), contract.redMax(), ours, whoop, props.holdoutWhoopEnd(), LocalDate.now(clock.withZone(zone)),
                props.holdoutRollingDays(), runs));
    }

    /**
     * What has arrived, so the coach knows what to import next (stage 1b-3 PR 3).
     *
     * @param lastScoredDay the ACTIVE model's last scored day (null without one)
     * @param recomputeNeeded an import finished after the last Recompute features run (or there was none)
     */
    public record Feed(LocalDate lastWhoopDay, LocalDate lastGarminDay, LocalDate lastScoredDay, Instant lastWhoopImport,
                       Instant lastGarminImport, Instant lastRecompute, boolean recomputeNeeded, LocalDate whoopEnd) {}

    public Feed feed(long athleteId) {
        athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var f = repo.feed(athleteId);
        LocalDate lastScored = models.active(athleteId)
                .flatMap(m -> scores.days(athleteId, m.version(), null, null).stream().map(ScoringRepository.StoredDay::date)
                        .max(java.util.Comparator.naturalOrder()))
                .orElse(null);
        boolean needed = f.lastImport() != null && (f.lastRecompute() == null || f.lastImport().isAfter(f.lastRecompute()));
        return new Feed(f.lastWhoopDay(), f.lastGarminDay(), lastScored, f.lastWhoopImport(), f.lastGarminImport(),
                f.lastRecompute(), needed, props.holdoutWhoopEnd());
    }

    /** The decision report as a downloadable HTML file (ADR 0013 D7: built on request, never stored). */
    public record ReportFile(String filename, String html) {}

    public ReportFile decisionReport(long athleteId, String version) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var r = report(athleteId, version);
        ZoneId zone = ZoneId.of(athlete.timezone());
        Instant now = clock.instant();
        String day = LocalDate.ofInstant(now, zone).toString();
        String safeVersion = r.modelVersion().replaceAll("[^A-Za-z0-9._-]", "_");
        return new ReportFile("holdout-decision-" + safeVersion + "-" + day + ".html", DecisionReport.render(r, athlete.name(), now, zone));
    }

    /** A stored score, reduced to what the comparison reads (unrounded values, as calibration compares them). */
    static HoldoutEvaluator.OurDay ourDay(ScoringRepository.StoredDay d, ScoringContract c) {
        JsonNode q = d.dataQuality();
        JsonNode loads = d.loads();
        Double recovery = null;
        if (d.recoveryPct() != null) {
            JsonNode raw = d.contributors() == null ? null : d.contributors().get("recovery_raw");
            recovery = raw != null && raw.isNumber()
                    ? Math.max(c.recovery().clipLow(), Math.min(c.recovery().clipHigh(), raw.asDouble()))
                    : d.recoveryPct().doubleValue();
        }
        String reason = null;
        JsonNode reasons = q == null ? null : q.get("recovery_reasons");
        if (reasons != null && reasons.isArray() && !reasons.isEmpty()) {
            JsonNode first = reasons.get(0);
            reason = first.path("reason").asText(null);
            if (reason != null && first.hasNonNull("detail")) reason = reason + "/" + first.get("detail").asText();
        }
        Double strain = d.strain() == null ? null : number(loads, "strain", d.strain().doubleValue());
        Double sleepPerf = d.sleepPerformancePct() == null ? null : number(loads, "sleep_performance", d.sleepPerformancePct().doubleValue());
        Double sleepNeed = number(loads, "sleep_need", d.sleepNeedMin() == null ? null : d.sleepNeedMin().doubleValue());
        return new HoldoutEvaluator.OurDay(d.date(), recovery, d.recoveryPct(), d.recoveryBand(), reason,
                q != null && q.path("recovery_lower_confidence").asBoolean(false),
                strain, d.strain() == null ? null : d.strain().doubleValue(), text(q, "strain_reason"),
                number(loads, "monitored_minutes", null), sleepPerf, d.sleepPerformancePct(), text(q, "sleep_performance_reason"),
                sleepNeed, q != null && q.path("profile_changed").asBoolean(false), q == null ? null : q.get("profile"),
                q != null && q.path("stale").asBoolean(false));
    }

    private static Double number(JsonNode n, String key, Double fallback) {
        JsonNode v = n == null ? null : n.get(key);
        // Not a ternary: "cond ? v.asDouble() : fallback" would unbox a null fallback.
        if (v != null && v.isNumber()) return v.asDouble();
        return fallback;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
