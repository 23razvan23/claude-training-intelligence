package app.training.metrics.trends;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.metrics.ScoringRepository.StoredDay;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Synthetic data only. The view lines values up by date and never fills a missing value in. */
class TrendsViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static StoredDay stored(LocalDate d, Integer recovery, String band, Double strain, boolean lowCoverage) throws Exception {
        return new StoredDay(d, "m", "f3", recovery, band, strain == null ? null : BigDecimal.valueOf(strain), 90, 450,
                JSON.readTree("{}"), JSON.readTree("{}"), JSON.readTree("{\"low_coverage\":" + lowCoverage + "}"), Instant.EPOCH);
    }

    @Test
    void mergesFeaturesAndScoresByDateAndLeavesGapsNull() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 11, 1);
        var features = Map.of(
                d1, new TrendsView.Features(52.0, 49, 430, 0.0),
                d1.plusDays(2), new TrendsView.Features(null, null, null, null));
        var scores = List.of(stored(d1, 70, "GREEN", 8.5, false), stored(d1.plusDays(1), null, null, null, true));
        var t = TrendsView.build("m", "f3", d1, d1.plusDays(3), d1, 365, features, scores);

        assertThat(t.days()).extracting(TrendsView.Day::date).containsExactly(d1, d1.plusDays(1), d1.plusDays(2));
        var first = t.days().get(0);
        assertThat(first.dayLoadEdwards()).isEqualTo(0.0); // a rest day is a real zero
        assertThat(first.rmssd()).isEqualTo(52.0);
        assertThat(first.strain()).isEqualTo(8.5);
        var scoredOnly = t.days().get(1); // no features row: unknown, not zero
        assertThat(scoredOnly.rmssd()).isNull();
        assertThat(scoredOnly.dayLoadEdwards()).isNull();
        assertThat(scoredOnly.strainLowCoverage()).isTrue();
        var featuresOnly = t.days().get(2);
        assertThat(featuresOnly.rmssd()).isNull();
        assertThat(featuresOnly.recoveryPct()).isNull();
    }
}
