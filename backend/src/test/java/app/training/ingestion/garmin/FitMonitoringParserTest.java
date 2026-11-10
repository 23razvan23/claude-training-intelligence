package app.training.ingestion.garmin;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.ingestion.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FitMonitoringParserTest {

    @Test
    void expandsTimestamp16IncludingRollover() {
        long last = 0x12345FFF0L;
        assertThat(FitMonitoringParser.expand(last, 0xFFF5)).isEqualTo(last + 5);
        // low 16 bits wrapped around: 0x0003 after 0xFFF0 is 19 s later
        assertThat(FitMonitoringParser.expand(last, 0x0003)).isEqualTo(last + 19);
        assertThat(FitMonitoringParser.expand(last, 0xFFF0)).isEqualTo(last);
    }

    @Test
    void parsesAllDayHrFromCompressedTimestamps() throws Exception {
        Instant start = Instant.parse("2026-03-01T10:00:00Z");
        var samples = FitMonitoringParser.parse(Fixtures.fitMonitoring(start, 1, 90, 62));
        assertThat(samples).hasSize(90);
        assertThat(samples.getFirst().ts()).isEqualTo(start);
        assertThat(samples.getLast().ts()).isEqualTo(start.plusSeconds(89 * 60));
        assertThat(samples).allMatch(s -> s.hr() == 62);
    }

    @Test
    void ignoresImplausibleHr() throws Exception {
        assertThat(FitMonitoringParser.parse(Fixtures.fitMonitoring(Instant.parse("2026-03-01T10:00:00Z"), 1, 10, 0))).isEmpty();
    }
}
