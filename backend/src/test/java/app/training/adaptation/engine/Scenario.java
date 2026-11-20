package app.training.adaptation.engine;

import app.training.adaptation.engine.Model.DayState;
import app.training.adaptation.engine.Model.Input;
import app.training.adaptation.engine.Model.Niggle;
import app.training.adaptation.engine.Model.Result;
import app.training.adaptation.engine.Model.Session;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A synthetic "given" for the scenario tests: signals of today and yesterday, the plan, the km run. */
final class Scenario {
    /** Monday 16 November 2026. */
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);

    LocalDate today = MONDAY;
    Integer recovery = 70;
    String band = "GREEN";
    int nonGreenStreak = 0;
    boolean beforeHrv = false;
    Integer sleepDebtMin = 0;
    Double sleepNights = 0.0;
    int sleepKnownNights = 7;
    boolean illnessToday = false;
    List<Niggle> niggles = new ArrayList<>();
    Double runningAcwr = 1.0;
    Double tolerance = 60.0;
    final Set<LocalDate> illnessDays = new HashSet<>();
    final Map<LocalDate, Double> runKm = new HashMap<>();
    final List<Session> sessions = new ArrayList<>();
    final Set<Long> skip = new HashSet<>();
    boolean restoreRecently = false;
    EngineSettings settings = EngineSettings.defaults();
    private long nextId = 1;

    Scenario recovery(String band, int pct) {
        this.band = band;
        this.recovery = pct;
        this.nonGreenStreak = "GREEN".equals(band) ? 0 : Math.max(1, nonGreenStreak);
        return this;
    }

    Scenario streak(int days) {
        this.nonGreenStreak = days;
        return this;
    }

    Scenario sleepDebt(int minutes, double nights) {
        this.sleepDebtMin = minutes;
        this.sleepNights = nights;
        return this;
    }

    Scenario niggle(String area, int daysAgo) {
        niggles.add(new Niggle(area, today.minusDays(daysAgo), daysAgo + 1));
        return this;
    }

    Scenario illness(int daysAgo) {
        illnessDays.add(today.minusDays(daysAgo));
        if (daysAgo == 0) illnessToday = true;
        return this;
    }

    Scenario ran(int daysAgo, double km) {
        runKm.merge(today.minusDays(daysAgo), km, Double::sum);
        return this;
    }

    /** A planned session {@code offset} days from today (negative: in the past). Returns its id. */
    long session(int offset, String type, String demand, Double km, Integer min) {
        return add(offset, type, demand, km, min, "PLANNED", false);
    }

    long add(int offset, String type, String demand, Double km, Integer min, String status, boolean taper) {
        long id = nextId++;
        sessions.add(new Session(id, 1L, today.plusDays(offset), type, demand, null, "workout " + id, km, min, null, status, taper, null));
        return id;
    }

    Input input() {
        var t = new DayState(today, beforeHrv ? null : recovery, beforeHrv ? null : band, nonGreenStreak, beforeHrv, sleepDebtMin, sleepNights,
                sleepKnownNights, illnessToday, List.copyOf(niggles), runningAcwr, tolerance, tolerance == null ? null : "computed");
        var y = new DayState(today.minusDays(1), 70, "GREEN", 0, false, 0, 0.0, 7, false, List.of(), runningAcwr, tolerance, "computed");
        return new Input(today, t, y, Set.copyOf(illnessDays), Map.copyOf(runKm), List.copyOf(sessions), Set.copyOf(skip), restoreRecently);
    }

    Result run() {
        return RulesEngine.evaluate(input(), settings);
    }
}
