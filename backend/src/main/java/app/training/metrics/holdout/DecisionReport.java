package app.training.metrics.holdout;

import app.training.metrics.holdout.HoldoutEvaluator.Figures;
import app.training.metrics.holdout.HoldoutEvaluator.Mark;
import app.training.metrics.holdout.HoldoutEvaluator.Report;
import app.training.metrics.holdout.HoldoutEvaluator.Stat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The decision report (ADR 0009 H6, H9; ADR 0013 D7): a self-contained HTML page, no scripts
 * and no remote loads, to keep when the Whoop subscription ends. Built from the same
 * {@link Report} as the Holdout view, every time it is asked for; nothing is stored.
 */
public final class DecisionReport {
    private DecisionReport() {}

    private static final String CSS = """
            body{font-family:-apple-system,system-ui,Segoe UI,Roboto,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#1d2433;line-height:1.45}
            h1{font-size:1.6rem;margin-bottom:.2rem}h2{font-size:1.15rem;margin-top:2rem;border-bottom:1px solid #d8dde6;padding-bottom:.2rem}
            table{border-collapse:collapse;width:100%;margin:.5rem 0;font-size:.92rem}th,td{border-bottom:1px solid #e4e8ef;padding:.3rem .5rem;text-align:left}
            td.n,th.n{text-align:right;font-variant-numeric:tabular-nums}.muted{color:#5c6577}.small{font-size:.85rem}
            .verdict{font-size:1.25rem;font-weight:700;padding:.8rem 1rem;border-radius:.4rem;margin:1rem 0}
            .pass{background:#e3f4e8;color:#185c2c}.notmet{background:#fbe5e3;color:#8a1f12}.wait{background:#fdf3d8;color:#6b4d00}
            .ok{color:#185c2c;font-weight:600}.bad{color:#8a1f12;font-weight:600}
            """;

    private static final Map<String, String> GROUPS = Map.ofEntries(
            Map.entry(HoldoutEvaluator.COMPARED, "Compared"),
            Map.entry(HoldoutEvaluator.COMPARED_LOW_COVERAGE, "Compared, low coverage (under the all-day HR minimum; no mark)"),
            Map.entry(HoldoutEvaluator.NO_WHOOP, "No Whoop score that day"),
            Map.entry(HoldoutEvaluator.NO_SCORE_YET, "No score from us yet (after our last data day: press Recompute features)"),
            Map.entry(HoldoutEvaluator.WAITS_FOR_EXPORT,
                    "Waits for a Garmin export (scored with Garmin's live breathing rate; counted once an exported night is scored)"),
            Map.entry("NO_DAY_DATA", "No Garmin data that day"),
            Map.entry("NO_GARMIN_CORE", "Missing resting HR, overnight HRV or sleep"),
            Map.entry("NO_HRV", "No overnight HRV"),
            Map.entry("NO_RESTING_HR", "No resting HR"),
            Map.entry("NO_RESPIRATION", "No respiration"),
            Map.entry("NO_SLEEP", "No sleep recorded"),
            Map.entry("NO_SPO2", "No SpO2"),
            Map.entry("NO_BODY_BATTERY", "No Body Battery"),
            Map.entry("MISSING_WORKOUT_HR", "A workout without heart rate"),
            Map.entry("NO_ALL_DAY_HR", "No all-day heart rate"),
            Map.entry("NO_ZONE_MINUTES", "No heart-rate zone minutes"));

    /** A group code in plain words; "NO_STRAIN_YESTERDAY/X" reads "No strain yesterday (X in words)". */
    public static String groupLabel(String code) {
        if (code.startsWith("NO_STRAIN_YESTERDAY")) {
            int slash = code.indexOf('/');
            return slash < 0 ? "No strain score yesterday"
                    : "No strain score yesterday (" + groupLabel(code.substring(slash + 1)).toLowerCase(Locale.ROOT) + ")";
        }
        return GROUPS.getOrDefault(code, code);
    }

    /** The verdict as one sentence, following ADR 0009 H6. */
    public static String verdictText(Report r) {
        var v = r.verdict();
        return switch (v.state()) {
            case NOT_ENOUGH_DAYS_YET -> "Not enough days yet: " + v.recoveryDays() + " of " + v.minRecoveryDays()
                    + " compared recovery days. No mark is judged before " + v.minRecoveryDays() + ".";
            case NOT_ENOUGH_DAYS_BY_RULE -> "Not enough days by the rule (" + v.recoveryDays() + " of " + v.minRecoveryDays()
                    + " compared recovery days). The decision is the coach's, taken knowing this.";
            case INTERIM -> (v.overall() == HoldoutEvaluator.Overall.PASS ? "Pass" : "Not met")
                    + " (interim: can still change until the last Whoop day).";
            case FINAL -> v.overall() == HoldoutEvaluator.Overall.PASS ? "Pass (final)." : "Not met (final).";
        };
    }

    public static String render(Report r, String athleteName, Instant generatedAt, ZoneId zone) {
        var b = new StringBuilder(32_000);
        b.append("<!doctype html><html lang='en'><head><meta charset='utf-8'><title>Holdout decision ")
                .append(e(r.modelVersion())).append("</title><style>").append(CSS).append("</style></head><body>");
        b.append("<h1>Live comparison with Whoop: decision report</h1>");
        b.append("<p class='muted'>").append(e(athleteName)).append(" · model ").append(e(r.modelVersion())).append(" (")
                .append(e(r.status())).append(") · generated ").append(e(when(generatedAt, zone))).append("</p>");

        var v = r.verdict();
        String cls = switch (v.state()) {
            case INTERIM, FINAL -> v.overall() == HoldoutEvaluator.Overall.PASS ? "pass" : "notmet";
            default -> "wait";
        };
        b.append("<div class='verdict ").append(cls).append("'>").append(e(verdictText(r))).append("</div>");
        if (r.phase() == HoldoutEvaluator.Phase.RETIRED) {
            b.append("<p>This version was retired on ").append(e(when(r.retiredAt(), zone)))
                    .append(" when another version was activated. Its comparison stopped then and is shown as it was (ADR 0009 H1, H8).</p>");
        }
        if (r.windowTo() != null && r.windowTo().isBefore(r.whoopEnd().minusDays(1)) && r.phase() != HoldoutEvaluator.Phase.RUNNING) {
            b.append("<p class='bad'>The last Whoop day in the database is ").append(r.windowTo())
                    .append(", before the end of the subscription (").append(r.whoopEnd())
                    .append("). Import the last Whoop export, then press Recompute features and download this report again.</p>");
        }

        b.append("<h2>Rules</h2><table><tbody>");
        row(b, "Protocol", e(r.protocol()) + " (fixed before any holdout day was seen)");
        row(b, "Window", r.windowFrom() + " to " + (r.windowTo() == null ? "no Whoop day yet" : r.windowTo().toString())
                + " (" + r.cumulative().days() + " days); Whoop ends " + r.whoopEnd());
        row(b, "Pass marks frozen", "at " + (r.activatedAt() == null ? "activation" : e(when(r.activatedAt(), zone))) + ", source: "
                + e(r.source()));
        row(b, "Minimum before a verdict", v.minRecoveryDays() + " compared recovery days");
        row(b, "Interval", "95% Wilson, next to every rate");
        b.append("</tbody></table>");

        b.append("<h2>Pass marks</h2><table><thead><tr><th>Mark</th><th class='n'>Target</th><th class='n'>Measured</th>")
                .append("<th class='n'>n</th><th class='n'>95% interval</th><th>Result</th></tr></thead><tbody>");
        for (Mark m : v.marks()) {
            b.append("<tr><td>").append(e(markLabel(m.key()))).append("</td><td class='n'>").append(m.comparison().equals(">=") ? "≥ " : "≤ ")
                    .append(markValue(m.key(), m.target())).append("</td><td class='n'>")
                    .append(m.value() == null ? "–" : markValue(m.key(), m.value())).append("</td><td class='n'>").append(m.n())
                    .append("</td><td class='n'>").append(m.rate() == null ? "–" : interval(m.rate())).append("</td><td>")
                    .append(m.met() == null ? "<span class='muted'>not judged yet</span>"
                            : m.met() ? "<span class='ok'>met</span>" : "<span class='bad'>not met</span>")
                    .append("</td></tr>");
        }
        b.append("</tbody></table><p class='small muted'>A mark is met when its measured value meets it; the interval shows how sure that is (coach decision D1). ")
                .append("The recovery marks matter most: they drive training decisions.</p>");

        b.append("<h2>Whole window (the evidence)</h2>");
        figures(b, r.cumulative());

        var nv = r.naive();
        b.append("<h2>Simple baseline: yesterday's Whoop as today's guess</h2>")
                .append("<p class='small muted'>On the ").append(nv.n())
                .append(" days where our recovery, Whoop's recovery and Whoop's recovery the day before all exist (")
                .append(nv.skippedNoWhoopYesterday()).append(nv.skippedNoWhoopYesterday() == 1 ? " compared day" : " compared days")
                .append(" skipped: no Whoop the day before). No pass mark.</p>")
                .append("<table><thead><tr><th></th><th class='n'>Band agreement</th><th class='n'>Green vs red</th><th class='n'>Recovery MAE</th></tr></thead><tbody>");
        side(b, "Our score", nv.ours());
        side(b, "Yesterday's Whoop", nv.naive());
        b.append("</tbody></table>");

        b.append("<h2>Days left out, by reason</h2>");
        int waiting = r.markers().waitingForExportDays();
        if (waiting > 0) {
            b.append("<p class='wait'>").append(waiting).append(waiting == 1 ? " compared day waits" : " compared days wait")
                    .append(" for a Garmin export: scored from a synced night (Garmin's live breathing rate), so not counted ")
                    .append("until an exported night replaces it and Recompute features runs (ADR 0015 D13).</p>");
        }
        groups(b, r.cumulative().groups());

        b.append("<h2>Markers</h2><ul>");
        b.append("<li>Recompute features runs in the window: ").append(r.markers().recomputeRuns().size());
        if (!r.markers().recomputeRuns().isEmpty()) {
            b.append(" (");
            var runs = r.markers().recomputeRuns();
            for (int i = 0; i < runs.size(); i++) b.append(i == 0 ? "" : ", ").append(e(when(runs.get(i), zone)));
            b.append(")");
        }
        b.append("</li><li>Days scored with a profile (HR max / resting HR) different from the model's: ")
                .append(r.markers().profileChangedDays()).append("</li>");
        b.append("<li>Days scored from features older than the latest import: ").append(r.markers().staleFeatureDays()).append("</li></ul>");

        b.append("<h2>Week by week (drift only, not the verdict)</h2><table><thead><tr><th>Week</th><th class='n'>Recovery n</th>")
                .append("<th class='n'>Band agreement</th><th class='n'>Green vs red</th><th class='n'>Strain n</th><th class='n'>Strain MAE</th></tr></thead><tbody>");
        for (Figures w : r.weeks()) {
            b.append("<tr><td>").append(w.from()).append(" – ").append(w.to()).append("</td><td class='n'>").append(w.recovery().n())
                    .append("</td><td class='n'>").append(rate(w.recovery().bandAgreement())).append("</td><td class='n'>")
                    .append(rate(w.recovery().greenRed())).append("</td><td class='n'>").append(w.strain().n()).append("</td><td class='n'>")
                    .append(stat(w.strain().mae(), 2)).append("</td></tr>");
        }
        b.append("</tbody></table>");

        b.append("<h2>Every day</h2><table class='small'><thead><tr><th>Day</th><th class='n'>Our recovery</th><th class='n'>Whoop</th>")
                .append("<th class='n'>Our strain</th><th class='n'>Whoop</th><th class='n'>Our sleep</th><th class='n'>Whoop</th><th>Note</th></tr></thead><tbody>");
        for (var d : r.days()) {
            String note = HoldoutEvaluator.COMPARED.equals(d.recoveryGroup()) ? (d.lowerConfidence() ? "recovery lower confidence" : "")
                    : groupLabel(d.recoveryGroup());
            if (d.lowCoverage()) note = (note.isEmpty() ? "" : note + "; ") + "strain low coverage";
            b.append("<tr><td>").append(d.date()).append("</td><td class='n'>").append(pctBand(d.ourRecovery(), d.ourBand()))
                    .append("</td><td class='n'>").append(pctBand(d.whoopRecovery(), d.whoopBand())).append("</td><td class='n'>")
                    .append(d.ourStrain() == null ? "–" : String.format(Locale.ROOT, "%.1f", d.ourStrain())).append("</td><td class='n'>")
                    .append(d.whoopStrain() == null ? "–" : String.format(Locale.ROOT, "%.1f", d.whoopStrain())).append("</td><td class='n'>")
                    .append(d.ourSleepPerf() == null ? "–" : d.ourSleepPerf() + "%").append("</td><td class='n'>")
                    .append(d.whoopSleepPerf() == null ? "–" : d.whoopSleepPerf() + "%").append("</td><td>").append(e(note)).append("</td></tr>");
        }
        b.append("</tbody></table>");
        b.append("<p class='small muted'>Rates: share of compared days, with the 95% Wilson interval. MAE: mean absolute difference, ours − Whoop. ")
                .append("Spearman: how well the two rank the days (1 = same order). Bands: green ≥ 67, red ≤ 33, from the unrounded recovery.</p>");
        b.append("</body></html>");
        return b.toString();
    }

    private static void figures(StringBuilder b, Figures f) {
        var r = f.recovery();
        b.append("<table><thead><tr><th>Score</th><th class='n'>n</th><th class='n'>Band agreement</th><th class='n'>Green vs red</th>")
                .append("<th class='n'>MAE</th><th class='n'>Spearman</th></tr></thead><tbody>");
        b.append("<tr><td>Recovery").append(r.lowerConfidence() > 0 ? " (" + r.lowerConfidence() + " lower-confidence days included)" : "")
                .append("</td><td class='n'>").append(r.n()).append("</td><td class='n'>").append(rate(r.bandAgreement()))
                .append("</td><td class='n'>").append(rate(r.greenRed())).append("</td><td class='n'>").append(stat(r.mae(), 1))
                .append("</td><td class='n'>").append(stat(r.spearman(), 2)).append("</td></tr>");
        b.append("<tr><td>Strain (full coverage)</td><td class='n'>").append(f.strain().n()).append("</td><td></td><td></td><td class='n'>")
                .append(stat(f.strain().mae(), 2)).append("</td><td class='n'>").append(stat(f.strain().spearman(), 2)).append("</td></tr>");
        b.append("<tr><td>Strain, low coverage (no mark)</td><td class='n'>").append(f.strainLowCoverage().n())
                .append("</td><td></td><td></td><td class='n'>").append(stat(f.strainLowCoverage().mae(), 2)).append("</td><td class='n'>")
                .append(stat(f.strainLowCoverage().spearman(), 2)).append("</td></tr>");
        b.append("<tr><td>Sleep performance (no mark)</td><td class='n'>").append(f.sleepPerformanceMae().n())
                .append("</td><td></td><td></td><td class='n'>").append(stat(f.sleepPerformanceMae(), 1)).append(" pts</td><td></td></tr>");
        b.append("<tr><td>Sleep need (no mark)</td><td class='n'>").append(f.sleepNeedMae().n())
                .append("</td><td></td><td></td><td class='n'>").append(stat(f.sleepNeedMae(), 0)).append(" min</td><td></td></tr>");
        b.append("</tbody></table>");
        var c = r.confusion();
        b.append("<table class='small'><thead><tr><th>Whoop ↓ / ours →</th><th class='n'>Green</th><th class='n'>Yellow</th><th class='n'>Red</th></tr></thead><tbody>");
        for (String w : HoldoutEvaluator.BANDS) {
            b.append("<tr><td>").append(cap(w)).append("</td>");
            for (String o : HoldoutEvaluator.BANDS) b.append("<td class='n'>").append(c.get(w).get(o)).append("</td>");
            b.append("</tr>");
        }
        b.append("</tbody></table>");
    }

    private static void groups(StringBuilder b, Map<String, Map<String, Integer>> g) {
        b.append("<table><thead><tr><th>Score</th><th>Group</th><th class='n'>Days</th></tr></thead><tbody>");
        for (var score : List.of("recovery", "strain", "sleepPerformance", "sleepNeed")) {
            var m = g.getOrDefault(score, Map.of());
            m.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(en ->
                    b.append("<tr><td>").append(scoreLabel(score)).append("</td><td>").append(e(groupLabel(en.getKey())))
                            .append("</td><td class='n'>").append(en.getValue()).append("</td></tr>"));
        }
        b.append("</tbody></table>");
    }

    private static void side(StringBuilder b, String label, HoldoutEvaluator.Side s) {
        b.append("<tr><td>").append(label).append("</td><td class='n'>").append(rate(s.bandAgreement())).append("</td><td class='n'>")
                .append(rate(s.greenRed())).append("</td><td class='n'>").append(stat(s.recoveryMae(), 1)).append("</td></tr>");
    }

    private static void row(StringBuilder b, String k, String v) {
        b.append("<tr><th>").append(k).append("</th><td>").append(v).append("</td></tr>");
    }

    static String markLabel(String key) {
        return switch (key) {
            case "recovery_band_agreement_min" -> "Recovery: same band as Whoop";
            case "recovery_green_red_max" -> "Recovery: green vs red flips";
            case "strain_mae_max" -> "Strain: mean difference (full-coverage days)";
            case "strain_spearman_min" -> "Strain: rank agreement (full-coverage days)";
            default -> key;
        };
    }

    private static String markValue(String key, double v) {
        return key.startsWith("recovery_") ? pct(v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String scoreLabel(String s) {
        return switch (s) {
            case "recovery" -> "Recovery";
            case "strain" -> "Strain";
            case "sleepPerformance" -> "Sleep performance";
            default -> "Sleep need";
        };
    }

    /** "88.9% (k/n, 80.1–94.0%)" or "–" without days. */
    static String rate(Wilson.Rate r) {
        if (r.n() == 0) return "–";
        return pct(r.value()) + " (" + r.k() + "/" + r.n() + ", " + interval(r) + ")";
    }

    private static String interval(Wilson.Rate r) {
        if (r.n() == 0) return "–";
        return String.format(Locale.ROOT, "%.1f–%.1f%%", 100 * r.low(), 100 * r.high());
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", 100 * v);
    }

    private static String stat(Stat s, int digits) {
        return s.value() == null ? "–" : String.format(Locale.ROOT, "%." + digits + "f", s.value());
    }

    private static String pctBand(Integer v, String band) {
        return v == null ? "–" : v + "% " + (band == null ? "" : cap(band));
    }

    private static String cap(String band) {
        return band.charAt(0) + band.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String when(Instant t, ZoneId zone) {
        return t == null ? "–" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(t);
    }

    private static String e(String s) {
        if (s == null) return "";
        var o = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> o.append("&lt;");
                case '>' -> o.append("&gt;");
                case '&' -> o.append("&amp;");
                case '"' -> o.append("&quot;");
                case '\'' -> o.append("&#39;");
                default -> o.append(c);
            }
        }
        return o.toString();
    }
}
