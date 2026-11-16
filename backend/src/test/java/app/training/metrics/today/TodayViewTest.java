package app.training.metrics.today;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.training.metrics.ScoringRepository.StoredDay;
import app.training.metrics.today.TodayView.State;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The Today screen's choices, on synthetic stored days (ADR 0014). */
class TodayViewTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate TODAY = LocalDate.parse("2026-11-16");
    static final TodayProperties P = TodayProperties.defaults();

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static StoredDay scored(LocalDate d, int recovery, String band, double strain) {
        return new StoredDay(d, "m-test", "f3", recovery, band, BigDecimal.valueOf(strain), 90, 450,
                json("{}"),
                json("{\"intercept\":55,\"terms\":{\"ln_rmssd_z\":{\"value\":0.8,\"points\":6.4},"
                        + "\"rhr_dev\":{\"value\":-2.0,\"points\":1.2},\"prev_strain\":{\"value\":14.0,\"points\":-3.1}}}"),
                json("{\"has_features\":true,\"low_coverage\":false,\"recovery_reasons\":[],\"recovery_lower_confidence\":false,"
                        + "\"drift\":[],\"profile_changed\":false,\"stale\":false}"),
                Instant.EPOCH);
    }

    static StoredDay noRecovery(LocalDate d, String reason, String detail) {
        return new StoredDay(d, "m-test", "f3", null, null, BigDecimal.valueOf(8.0), 88, 440, json("{}"),
                json("{\"intercept\":55,\"terms\":{}}"),
                json("{\"has_features\":true,\"low_coverage\":false,\"recovery_reasons\":[{\"reason\":\"" + reason
                        + "\",\"detail\":\"" + detail + "\"}],\"recovery_lower_confidence\":false,\"drift\":[],"
                        + "\"profile_changed\":false,\"stale\":false}"),
                Instant.EPOCH);
    }

    static TodayView.Today build(List<StoredDay> rows) {
        return TodayView.build(TODAY, "Europe/Bucharest", "m-test", rows, new TreeMap<>(), Map.of(), null, P, 30, 14);
    }

    @Test
    void freshWhenTodayHasARecovery() {
        var t = build(List.of(scored(TODAY.minusDays(1), 40, "YELLOW", 9), scored(TODAY, 72, "GREEN", 4.2)));
        assertThat(t.state()).isEqualTo(State.FRESH);
        assertThat(t.day().date()).isEqualTo(TODAY);
        assertThat(t.day().recoveryPct()).isEqualTo(72);
        assertThat(t.lastScored()).isNull();
        assertThat(t.lastDataDate()).isEqualTo(TODAY);
    }

    @Test
    void yesterdaysRecoveryIsNeverShownAsToday() {
        var t = build(List.of(scored(TODAY.minusDays(2), 55, "YELLOW", 9), scored(TODAY.minusDays(1), 80, "GREEN", 12)));
        assertThat(t.state()).isEqualTo(State.STALE);
        assertThat(t.day()).as("no 'today' when today has no row").isNull();
        assertThat(t.lastScored().date()).isEqualTo(TODAY.minusDays(1));
        assertThat(t.lastDataDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void staleWhenTheNewestDataIsOld() {
        var t = build(List.of(scored(LocalDate.parse("2026-11-08"), 66, "YELLOW", 10)));
        assertThat(t.state()).isEqualTo(State.STALE);
        assertThat(t.lastDataDate()).isEqualTo(LocalDate.parse("2026-11-08"));
        assertThat(t.today()).isEqualTo(TODAY);
    }

    @Test
    void rowsAfterTodayAreIgnored() {
        // A clock or timezone mismatch must not turn tomorrow's row into today's.
        var t = build(List.of(scored(TODAY.minusDays(1), 60, "YELLOW", 9), scored(TODAY.plusDays(1), 90, "GREEN", 3)));
        assertThat(t.state()).isEqualTo(State.STALE);
        assertThat(t.lastScored().date()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void missingWithTheStoredReason() {
        var t = build(List.of(scored(TODAY.minusDays(1), 60, "YELLOW", 9), noRecovery(TODAY, "NO_STRAIN_YESTERDAY", "NO_ALL_DAY_HR")));
        assertThat(t.state()).isEqualTo(State.MISSING);
        assertThat(t.day().recoveryPct()).isNull();
        assertThat(t.day().recoveryReasons()).containsExactly(new TodayView.Reason("NO_STRAIN_YESTERDAY", "NO_ALL_DAY_HR"));
        assertThat(t.day().strain()).isEqualByComparingTo("8.0");
        assertThat(t.day().strainTarget()).as("no band, no target").isNull();
        assertThat(t.day().contributions()).isEmpty();
    }

    @Test
    void noModelAndNoData() {
        assertThat(TodayView.empty(TODAY, "Europe/Bucharest", null).state()).isEqualTo(State.NO_MODEL);
        assertThat(build(List.of()).state()).isEqualTo(State.NO_DATA);
    }

    @Test
    void lowConfidenceFlagsAreCarried() {
        var r = scored(TODAY, 63, "YELLOW", 5.3);
        var q = (com.fasterxml.jackson.databind.node.ObjectNode) r.dataQuality();
        q.put("low_coverage", true);
        q.put("recovery_lower_confidence", true);
        q.put("stale", true);
        var t = build(List.of(r));
        assertThat(t.day().strainLowCoverage()).isTrue();
        assertThat(t.day().recoveryLowerConfidence()).isTrue();
        assertThat(t.day().needsRecompute()).isTrue();
    }

    @Test
    void contributionsLargestFirstWithHrvPercentAgainstTheBaseline() {
        var ln = new TreeMap<LocalDate, Double>();
        var ms = new HashMap<LocalDate, Double>();
        for (int i = 1; i <= 20; i++) ln.put(TODAY.minusDays(i), Math.log(50)); // baseline 50 ms
        ln.put(TODAY, Math.log(54));
        ms.put(TODAY, 54.0);
        var t = TodayView.build(TODAY, "Europe/Bucharest", "m-test", List.of(scored(TODAY, 70, "GREEN", 4)), ln, ms, null, P, 30, 14);
        var c = t.day().contributions();
        assertThat(c).extracting(TodayView.Contribution::feature).containsExactly("ln_rmssd_z", "prev_strain", "rhr_dev");
        assertThat(c.get(0).measured()).isEqualTo(54.0);
        assertThat(c.get(0).baseline()).isCloseTo(50.0, within(1e-9));
        assertThat(c.get(0).percentVsBaseline()).isCloseTo(8.0, within(1e-9));
        assertThat(c.get(1).percentVsBaseline()).isNull();
    }

    @Test
    void noHrvPercentWithoutEnoughBaselineDays() {
        var ln = new TreeMap<LocalDate, Double>();
        for (int i = 1; i <= 13; i++) ln.put(TODAY.minusDays(i), Math.log(50));
        ln.put(TODAY, Math.log(54));
        var t = TodayView.build(TODAY, "Europe/Bucharest", "m-test", List.of(scored(TODAY, 70, "GREEN", 4)), ln, Map.of(), null, P, 30, 14);
        assertThat(t.day().contributions().get(0).percentVsBaseline()).isNull();
    }

    /** 28 days with strain 1..28 before the shown day. */
    static List<StoredDay> window(String band) {
        var rows = new ArrayList<StoredDay>();
        for (int i = 28; i >= 1; i--) rows.add(scored(TODAY.minusDays(i), 50, "YELLOW", 29 - i));
        rows.add(scored(TODAY, band.equals("GREEN") ? 80 : band.equals("RED") ? 20 : 50, band, 3));
        return rows;
    }

    @Test
    void strainTargetFollowsTheBandAndTheAthletesOwnRange() {
        // Strains 1..28: median 14.5, 25th percentile 7.75, 75th percentile 21.25.
        var green = build(window("GREEN")).day().strainTarget();
        assertThat(green.low()).isEqualTo(14.5);
        assertThat(green.high()).isEqualTo(21.3);
        assertThat(green.basisDays()).isEqualTo(28);
        var yellow = build(window("YELLOW")).day().strainTarget();
        assertThat(yellow.low()).isEqualTo(7.8);
        assertThat(yellow.high()).isEqualTo(14.5);
        var red = build(window("RED")).day().strainTarget();
        assertThat(red.low()).isEqualTo(0.0);
        assertThat(red.high()).isEqualTo(7.8);
    }

    @Test
    void strainTargetSkipsLowCoverageDaysAndNeedsEnoughDays() {
        var rows = new ArrayList<StoredDay>();
        for (int i = 20; i >= 1; i--) {
            var r = scored(TODAY.minusDays(i), 50, "YELLOW", 10);
            if (i <= 7) ((com.fasterxml.jackson.databind.node.ObjectNode) r.dataQuality()).put("low_coverage", true);
            rows.add(r);
        }
        rows.add(scored(TODAY, 80, "GREEN", 3));
        assertThat(build(rows).day().strainTarget()).as("13 full-coverage days < 14").isNull();
    }

    @Test
    void percentileIsLinearBetweenRanks() {
        assertThat(TodayView.percentile(List.of(1.0, 2.0, 3.0, 4.0), 50)).isEqualTo(2.5);
        assertThat(TodayView.percentile(List.of(5.0), 75)).isEqualTo(5.0);
    }
}
