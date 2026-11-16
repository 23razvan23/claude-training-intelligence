package app.training.metrics.holdout;

import static app.training.metrics.holdout.HoldoutEvaluatorTest.FROM;
import static app.training.metrics.holdout.HoldoutEvaluatorTest.RUNNING;
import static app.training.metrics.holdout.HoldoutEvaluatorTest.WHOOP_END;
import static app.training.metrics.holdout.HoldoutEvaluatorTest.input;
import static app.training.metrics.holdout.HoldoutEvaluatorTest.oursDays;
import static app.training.metrics.holdout.HoldoutEvaluatorTest.replace;
import static app.training.metrics.holdout.HoldoutEvaluatorTest.whoopDays;
import static app.training.metrics.holdout.HoldoutEvaluatorTest.withRecovery;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** ADR 0009 H6 and H9: what the decision report must state, on synthetic comparisons. */
class DecisionReportTest {
    static final Instant NOW = Instant.parse("2026-11-16T08:00:00Z");
    static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");

    private static String html(HoldoutEvaluator.Report r) {
        return DecisionReport.render(r, "Coach", NOW, ZONE);
    }

    @Test
    void showsEveryItemTheProtocolRequires() {
        var whoop = new ArrayList<>(whoopDays(FROM.minusDays(1), 31));
        whoop.removeIf(w -> w.date().equals(FROM.plusDays(4)));
        // Whoop to FROM+29; our scores to FROM+28.
        var r = HoldoutEvaluator.evaluate(input(oursDays(FROM, 29), whoop, RUNNING, "ACTIVE"));
        String h = html(r);
        // Window dates, protocol, the frozen marks and their source.
        assertThat(h).contains("2026-11-09 to 2026-12-08 (30 days)").contains("Whoop ends 2027-02-19");
        assertThat(h).contains("ADR 0009").contains("source: V15");
        assertThat(h).contains("≥ 70.0%").contains("≤ 3.0%").contains("≤ 2.00").contains("≥ 0.80");
        assertThat(h).contains("90 compared recovery days");
        // n compared and every rate with its interval (28/28 → 87.9–100.0%).
        assertThat(h).contains("100.0% (28/28, 87.9–100.0%)");
        // Days left out, by reason: Whoop missing on one day; our score missing on the last one.
        assertThat(h).contains("No Whoop score that day</td><td class='n'>1");
        assertThat(h).contains("No score from us yet");
        // The naive baseline next to ours.
        assertThat(h).contains("Yesterday's Whoop").contains("Our score");
        // The verdict state.
        assertThat(h).contains("Not enough days yet: 28 of 90 compared recovery days");
        assertThat(h).contains("not judged yet");
    }

    @Test
    void statesEachVerdictInWords() {
        var pass = HoldoutEvaluator.evaluate(input(oursDays(FROM, 90), whoopDays(FROM, 90), RUNNING, "ACTIVE"));
        assertThat(DecisionReport.verdictText(pass)).isEqualTo("Pass (interim: can still change until the last Whoop day).");
        assertThat(html(pass)).contains("class='verdict pass'").contains("<span class='ok'>met</span>");

        var ours = oursDays(FROM, 95);
        for (int i : new int[] {10, 20, 30}) ours = replace(ours, FROM.plusDays(i), o -> withRecovery(o, 20, false));
        var notMet = HoldoutEvaluator.evaluate(input(ours, whoopDays(FROM, 95), WHOOP_END.plusDays(1), "ACTIVE"));
        assertThat(DecisionReport.verdictText(notMet)).isEqualTo("Not met (final).");
        assertThat(html(notMet)).contains("<span class='bad'>not met</span>");
        // The window ended before the subscription: the report asks for the last export.
        assertThat(html(notMet)).contains("Import the last Whoop export");

        var short_ = HoldoutEvaluator.evaluate(input(oursDays(FROM, 60), whoopDays(FROM, 60), WHOOP_END.plusDays(1), "ACTIVE"));
        assertThat(DecisionReport.verdictText(short_))
                .isEqualTo("Not enough days by the rule (60 of 90 compared recovery days). The decision is the coach's, taken knowing this.");

        var retired = HoldoutEvaluator.evaluate(input(oursDays(FROM, 30), whoopDays(FROM, 30), RUNNING, "RETIRED"));
        assertThat(html(retired)).contains("This version was retired");
    }

    @Test
    void reasonsReadInPlainWords() {
        assertThat(DecisionReport.groupLabel("NO_HRV")).isEqualTo("No overnight HRV");
        assertThat(DecisionReport.groupLabel("NO_STRAIN_YESTERDAY/MISSING_WORKOUT_HR"))
                .isEqualTo("No strain score yesterday (a workout without heart rate)");
        assertThat(DecisionReport.groupLabel("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
    }

    @Test
    void isStaticAndEscapesText() {
        var r = HoldoutEvaluator.evaluate(input(oursDays(FROM, 3), whoopDays(FROM, 3), RUNNING, "ACTIVE"));
        String h = DecisionReport.render(r, "<script>alert(1)</script>", NOW, ZONE);
        assertThat(h).doesNotContain("<script").contains("&lt;script&gt;");
        assertThat(h).doesNotContain("http://").doesNotContain("https://");
    }
}
