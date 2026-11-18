package app.training.adaptation.signals;

import java.time.LocalDate;
import java.util.List;

/** Every signal of one athlete on one day (ADR 0021), as stored in day_signals.signals. */
public record DaySignals(LocalDate date, LoadSignals.Day load, Readiness.Day readiness, SleepDebt.Day sleep, Subjective.Day subjective,
                         Sessions sessions, Tolerance.Day tolerance) {

    /**
     * Session demand (SPEC.md Module 3): the day's planned sessions that still count, and the highest demand among
     * them (KEY > SUPPORTING > EASY; none without a session).
     */
    public record Sessions(String maxDemand, List<SignalRepository.PlannedSession> planned) {
        static Sessions of(List<SignalRepository.PlannedSession> planned) {
            String max = null;
            for (var p : planned) if (rank(p.demand()) > rank(max)) max = p.demand();
            return new Sessions(max, List.copyOf(planned));
        }

        private static int rank(String demand) {
            return demand == null ? 0 : switch (demand) {
                case "KEY" -> 3;
                case "SUPPORTING" -> 2;
                default -> 1;
            };
        }
    }
}
