package app.training.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Normalized rows the importers produce, one record per raw table. */
public final class RawRows {
    private RawRows() {}

    public static final String GARMIN = "GARMIN";
    public static final String WHOOP = "WHOOP";

    public record Wellness(
            String source, String sourceId, LocalDate calendarDate, Integer restingHr, BigDecimal hrvRmssdMs,
            String hrvStatus, BigDecimal respirationRate, BigDecimal spo2Pct, Integer stressAvg,
            Integer bodyBatteryHigh, Integer bodyBatteryLow) {}

    public record Sleep(
            String source, String sourceId, Instant startAt, Instant endAt, LocalDate cycleDate, boolean nap,
            Integer deepS, Integer lightS, Integer remS, Integer awakeS, Integer sleepScore,
            BigDecimal avgRespiration, BigDecimal avgSleepStress, BigDecimal avgSpo2Pct, BigDecimal lowestSpo2Pct) {}

    public record Activity(
            String source, String sourceId, String sport, String sourceSport, Instant startAt, LocalDate calendarDate,
            Integer durationS, BigDecimal distanceM, BigDecimal elevationGainM, Integer avgHr, Integer maxHr,
            Integer calories, BigDecimal trainingLoad, BigDecimal aerobicTe, BigDecimal anaerobicTe,
            BigDecimal whoopStrain) {}

    public record StreamPoint(
            Instant ts, Integer hr, Float speedMps, Integer cadence, Integer power, Float altitudeM, Float distanceM) {}

    public record WhoopCycle(
            Instant cycleStart, Instant cycleEnd, LocalDate cycleDate, Integer recoveryPct, BigDecimal dayStrain,
            BigDecimal hrvRmssdMs, Integer restingHr, BigDecimal skinTempC, BigDecimal spo2Pct,
            BigDecimal respirationRate, Integer sleepPerformancePct, Integer sleepNeedMin, Integer sleepDebtMin,
            Integer asleepMin, Integer energyKcal) {}

    public record JournalEntry(Instant cycleStart, LocalDate cycleDate, String question, Boolean answeredYes, String notes) {}
}
