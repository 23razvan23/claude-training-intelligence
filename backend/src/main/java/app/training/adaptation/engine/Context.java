package app.training.adaptation.engine;

import app.training.adaptation.engine.Model.Change;
import app.training.adaptation.engine.Model.DayState;
import app.training.adaptation.engine.Model.Input;
import app.training.adaptation.engine.Model.Reason;
import app.training.adaptation.engine.Model.Session;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** One engine run's view of the plan and the signals, with the helpers the rules share. Pure. */
final class Context {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH);
    private static final Map<String, String> TYPE_NAMES = Map.ofEntries(Map.entry("easy", "easy run"), Map.entry("recovery", "recovery run"),
            Map.entry("long", "long run"), Map.entry("long_fast_finish", "long run with fast finish"),
            Map.entry("threshold", "threshold session"), Map.entry("vo2max", "VO2max session"), Map.entry("race_pace", "race-pace session"),
            Map.entry("hills", "hill session"), Map.entry("strides", "easy run with strides"), Map.entry("race", "race"),
            Map.entry("cross", "cross-training"), Map.entry("rest", "rest day"));

    final Input in;
    final EngineSettings s;
    final LocalDate today;
    /** The last day the run reviews. */
    final LocalDate end;

    Context(Input in, EngineSettings s) {
        this.in = in;
        this.s = s;
        this.today = in.today();
        this.end = today.plusDays(s.horizonDays() - 1);
    }

    DayState todayState() {
        return in.todayState();
    }

    /** Sessions the run may change: PLANNED, inside the window, not decided on today, not a race (races are the coach's call). */
    List<Session> changeable() {
        return in.sessions().stream()
                .filter(x -> x.planned() && !x.date().isBefore(today) && !x.date().isAfter(end) && !x.race()
                        && !in.skipSessions().contains(x.id()))
                .sorted(Comparator.comparing(Session::date).thenComparing(Session::id))
                .toList();
    }

    List<Session> changeableOn(LocalDate d) {
        return changeable().stream().filter(x -> x.date().equals(d)).toList();
    }

    /** Sessions on a day that are still part of the plan (any status but skipped or replaced). */
    List<Session> on(LocalDate d) {
        return in.sessions().stream().filter(x -> x.counts() && x.date().equals(d)).toList();
    }

    /** Guardrail: race week (the days up to and including a race) and taper sessions get only reductions. */
    boolean protectedDay(LocalDate d) {
        for (Session x : in.sessions()) {
            if (!x.counts()) continue;
            if (x.race() && !d.isAfter(x.date()) && d.isAfter(x.date().minusDays(s.raceWeekDays()))) return true;
            if (x.taper() && x.date().equals(d)) return true;
        }
        return false;
    }

    boolean protectedSession(Session x) {
        return x.taper() || protectedDay(x.date());
    }

    /**
     * A day a key session can move to (guardrail: protect key sessions by moving them): after today, between
     * {@code from} and {@code to}, with no other session except a rest day, no other key session the day before or
     * after (hard and easy days alternate), and outside race week and taper.
     */
    Optional<LocalDate> freeDay(Session moving, LocalDate from, LocalDate to) {
        for (LocalDate d = from.isAfter(today) ? from : today.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (protectedDay(d)) continue;
            boolean busy = false;
            for (Session x : on(d)) if (x.id() != moving.id() && !"rest".equals(x.type())) busy = true;
            for (Session x : on(d.minusDays(1))) if (x.id() != moving.id() && x.key()) busy = true;
            for (Session x : on(d.plusDays(1))) if (x.id() != moving.id() && x.key()) busy = true;
            if (!busy) return Optional.of(d);
        }
        return Optional.empty();
    }

    /** An easy session in place of a hard one: {@code easyShare} of its distance and time. */
    Change easyReplacement(Session x, String why) {
        double f = s.easyShare();
        return new Change(x.date(), "easy", "EASY", "Easy run (instead of " + label(x) + ")",
                "Engine: easy run, conversational pace, instead of the planned " + label(x) + " (" + why + ")." + original(x),
                x.distanceKm() == null ? null : round1(x.distanceKm() * f),
                x.durationMin() == null ? null : (int) Math.round(x.durationMin() * f));
    }

    Change restReplacement(Session x, String why) {
        return new Change(x.date(), "rest", "EASY", "Rest (instead of " + label(x) + ")",
                "Engine: rest, or very easy movement by feel (" + why + ")." + original(x), null, null);
    }

    /** The same session, distance and time cut by {@code share}, with a note on how to cut it. */
    Change reduced(Session x, double share, String how) {
        double f = 1 - share;
        return Change.of(x).withVolume(x.distanceKm() == null ? null : round1(x.distanceKm() * f),
                        x.durationMin() == null ? null : (int) Math.round(x.durationMin() * f))
                .withDescription("Engine: " + how + "\n" + nz(x.description()));
    }

    private static String original(Session x) {
        return x.description() == null || x.description().isBlank() ? "" : "\nPlanned: " + x.description();
    }

    static String nz(String v) {
        return v == null ? "" : v;
    }

    static String label(Session x) {
        return x.title() != null && !x.title().isBlank() ? x.title() : TYPE_NAMES.getOrDefault(x.type(), x.type());
    }

    static String capital(String v) {
        return v == null || v.isEmpty() ? v : Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    static String day(LocalDate d) {
        return DAY.format(d);
    }

    static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    static String num(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static String pct(double share) {
        return Math.round(share * 100) + "%";
    }

    static Reason reason(String code, String text, Object... kv) {
        var params = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) params.put((String) kv[i], kv[i + 1]);
        return new Reason(code, text, params);
    }

    static Map<String, Object> details(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
