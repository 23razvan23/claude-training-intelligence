package app.training.metrics.trends;

import app.training.metrics.ScoringRepository.StoredDay;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The Trends response (stage 1b-2b, ADR 0016). Pure: it only lines up stored values by date and fills nothing in. */
public final class TrendsView {
    private TrendsView() {}

    /** One day. Every value is null when it was not recorded; the screen draws that as a gap, never as zero. */
    public record Day(
            LocalDate date, Double rmssd, Integer rhr, Integer sleepMinutes, Double dayLoadEdwards,
            Integer recoveryPct, String band, Double strain, boolean strainLowCoverage,
            Integer sleepPerformancePct, Integer sleepNeedMin) {}

    /** The stored feature values of one day (day_features). */
    public record Features(Double rmssd, Integer rhr, Integer sleepMinutes, Double dayLoadEdwards) {}

    /**
     * @param from first date asked for (the warm-up included)
     * @param to last date asked for, today in the athlete's timezone
     * @param firstHrvDate the first day with overnight HRV in all of the athlete's data, not only in the window
     * @param historyDays the longest range the screen offers; days before {@code to - historyDays} are warm-up only
     */
    public record Trends(
            String modelVersion, String featureVersion, LocalDate from, LocalDate to, LocalDate firstHrvDate,
            int historyDays, List<Day> days) {}

    public static Trends build(String modelVersion, String featureVersion, LocalDate from, LocalDate to,
                               LocalDate firstHrvDate, int historyDays,
                               Map<LocalDate, Features> features, List<StoredDay> scores) {
        var dates = new TreeMap<LocalDate, Object[]>();
        features.forEach((d, f) -> dates.computeIfAbsent(d, k -> new Object[2])[0] = f);
        for (StoredDay s : scores) dates.computeIfAbsent(s.date(), k -> new Object[2])[1] = s;
        var days = new ArrayList<Day>();
        dates.forEach((d, pair) -> {
            Features f = (Features) pair[0];
            StoredDay s = (StoredDay) pair[1];
            days.add(new Day(d,
                    f == null ? null : f.rmssd(), f == null ? null : f.rhr(), f == null ? null : f.sleepMinutes(),
                    f == null ? null : f.dayLoadEdwards(),
                    s == null ? null : s.recoveryPct(), s == null ? null : s.recoveryBand(),
                    s == null || s.strain() == null ? null : s.strain().doubleValue(), s != null && lowCoverage(s),
                    s == null ? null : s.sleepPerformancePct(), s == null ? null : s.sleepNeedMin()));
        });
        return new Trends(modelVersion, featureVersion, from, to, firstHrvDate, historyDays, days);
    }

    private static boolean lowCoverage(StoredDay s) {
        JsonNode q = s.dataQuality();
        return q != null && q.path("low_coverage").asBoolean(false);
    }

    public static Trends empty(LocalDate from, LocalDate to, int historyDays) {
        return new Trends(null, null, from, to, null, historyDays, List.of());
    }
}
