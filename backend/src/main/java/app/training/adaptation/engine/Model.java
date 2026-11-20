package app.training.adaptation.engine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The engine's inputs and outputs (ADR 0022). Plain values, so the rules are pure and testable "given → expect". */
public final class Model {
    private Model() {}

    public enum Action { KEEP, REDUCE, SWAP, REPLACE, MOVE, ADD }

    public enum Confidence { LOW, MEDIUM, HIGH }

    /** A planned session as the engine sees it. Distance in km. */
    public record Session(long id, Long planId, LocalDate date, String type, String demand, String title, String description,
                          Double distanceKm, Integer durationMin, Double estStrain, String status, boolean taper, String notes) {
        public boolean planned() {
            return "PLANNED".equals(status);
        }

        /** Still part of the plan (not skipped or replaced). */
        public boolean counts() {
            return !"SKIPPED".equals(status) && !"REPLACED".equals(status);
        }

        /** Q3 (ADR 0022): a hard session is one marked key or supporting; a rest day never is. */
        public boolean hard() {
            return !"rest".equals(type) && ("KEY".equals(demand) || "SUPPORTING".equals(demand));
        }

        public boolean key() {
            return "KEY".equals(demand);
        }

        public boolean race() {
            return "race".equals(type);
        }

        public boolean running() {
            return RUN_TYPES.contains(type);
        }

        public boolean easyRun() {
            return EASY_RUN_TYPES.contains(type);
        }

        public boolean longRun() {
            return "long".equals(type) || "long_fast_finish".equals(type);
        }

        public boolean intensity() {
            return INTENSITY_TYPES.contains(type);
        }

        public double km() {
            return distanceKm == null ? 0 : distanceKm;
        }
    }

    public static final Set<String> RUN_TYPES =
            Set.of("easy", "recovery", "long", "long_fast_finish", "threshold", "vo2max", "race_pace", "hills", "strides", "race");
    /** Trimmed first and lengthened when volume is restored (SPEC.md: "trim easy runs first"). */
    public static final Set<String> EASY_RUN_TYPES = Set.of("easy", "recovery", "strides");
    /** Work at intensity, removed while a niggle is open (SPEC.md niggle rule). Strides are short and fast, so included. */
    public static final Set<String> INTENSITY_TYPES = Set.of("threshold", "vo2max", "race_pace", "hills", "long_fast_finish", "strides");

    /** What a session becomes: its whole target (for REPLACE, the new session). */
    public record Change(LocalDate date, String type, String demand, String title, String description, Double distanceKm,
                         Integer durationMin) {
        public static Change of(Session s) {
            return new Change(s.date(), s.type(), s.demand(), s.title(), s.description(), s.distanceKm(), s.durationMin());
        }

        public Change withDate(LocalDate d) {
            return new Change(d, type, demand, title, description, distanceKm, durationMin);
        }

        public Change withVolume(Double km, Integer min) {
            return new Change(date, type, demand, title, description, km, min);
        }

        public Change withDescription(String text) {
            return new Change(date, type, demand, title, text, distanceKm, durationMin);
        }
    }

    /** One plain-language reason: a code and its numbers (for a translation later) and the English text. */
    public record Reason(String code, String text, Map<String, Object> params) {}

    /**
     * One proposed change. {@code counterpart} is the other session of a SWAP; {@code after}/{@code counterpartAfter}
     * what each becomes. {@code rule} names the starting rule that made it.
     */
    public record Proposal(String rule, Action action, Session session, Change after, Session counterpart, Change counterpartAfter,
                           List<Reason> reasons, Confidence confidence, Map<String, Object> details) {
        public Proposal withReasons(List<Reason> r) {
            return new Proposal(rule, action, session, after, counterpart, counterpartAfter, r, confidence, details);
        }
    }

    /** A niggle episode open on the day (ADR 0021 D11). */
    public record Niggle(String location, LocalDate since, int persistDays) {}

    /** The signals of one day the rules read (from day_signals). Missing numbers are null. */
    public record DayState(LocalDate date, Integer recoveryPct, String band, int nonGreenStreakDays, boolean beforeHrv,
                           Integer sleepDebtMin, Double sleepNightsOfNeed, int sleepKnownNights, boolean illness, List<Niggle> niggles,
                           Double runningAcwr, Double toleranceKmPerWeek, String toleranceSource) {}

    /**
     * Everything the rules see on one engine run.
     * <ul>
     *   <li>{@code today}: the athlete's local date; the run reviews {@code today} … {@code today + horizon - 1}.</li>
     *   <li>{@code todayState}: today's signals (readiness from today's score, sleep debt up to last night, today's
     *       check-ins, tolerance); null when none are stored yet.</li>
     *   <li>{@code yesterdayState}: yesterday's signals, for the load numbers (today's load is not finished, ADR 0021 D5).</li>
     *   <li>{@code illnessDays}: recent days with an illness report.</li>
     *   <li>{@code runKmByDay}: running km actually done per day (Garmin), past days and today.</li>
     *   <li>{@code sessions}: the plan around the window (past weeks for "under plan", later days for race week), all statuses.</li>
     *   <li>{@code skipSessions}: sessions with a suggestion already decided today; they are left alone until tomorrow.</li>
     *   <li>{@code restoreStepRecently}: a volume-restoring suggestion was accepted in the last 7 days (one step at a time).</li>
     * </ul>
     */
    public record Input(LocalDate today, DayState todayState, DayState yesterdayState, Set<LocalDate> illnessDays,
                        Map<LocalDate, Double> runKmByDay, List<Session> sessions, Set<Long> skipSessions, boolean restoreStepRecently) {}

    /** The proposals of one run, the health messages, and notes on what could not be checked. */
    public record Result(List<Proposal> proposals, List<Reason> healthMessages, List<String> notes) {}

    public static LocalDate monday(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    public static boolean sameWeek(LocalDate a, LocalDate b) {
        return monday(a).equals(monday(b));
    }
}
