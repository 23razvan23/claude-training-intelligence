package app.training.adaptation.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import app.training.adaptation.settings.SettingsRepository;
import app.training.adaptation.settings.SettingsService;
import app.training.security.CurrentUser;
import app.training.security.Role;
import app.training.security.UserRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The load signals over a synthetic year against a real database (stage 2-2, ADR 0021): a steady base, a watch-off
 * fortnight, a hard block, and a taper, with runs four days a week and cycling on Saturdays. Synthetic data only.
 */
@AutoConfigureMockMvc
class SignalsIT extends IntegrationTest {
    @Autowired SignalService signals;
    @Autowired SettingsService settings;
    @Autowired SettingsRepository settingsRepo;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;

    static final int DAYS = 365;
    static final int GAP_FROM = 120, GAP_TO = 133;      // watch off: no record at all
    static final int HARD_FROM = 300, HARD_TO = 320;    // a three-week hard block
    static final int TAPER_FROM = 340;

    record Day(Double total, Double banister, double runKm, double runMin, Double runLoad) {}

    /** The synthetic year: index 0 is DAYS-1 days ago, the last index is today. */
    static Map<Integer, Day> year() {
        var out = new HashMap<Integer, Day>();
        for (int i = 0; i < DAYS; i++) {
            if (i >= GAP_FROM && i <= GAP_TO) continue;
            int dow = i % 7;
            double scale = i >= HARD_FROM && i <= HARD_TO ? 1.8 : i >= TAPER_FROM ? 0.6 : 1.0;
            boolean run = dow == 0 || dow == 2 || dow == 4 || dow == 6;
            double km = run ? (dow == 6 ? 20 : 10) * scale : 0;
            double runLoad = run ? km * 9 : 0;
            double bike = dow == 5 ? 80 * scale : 0;
            out.put(i, new Day(runLoad + bike, (runLoad + bike) * 0.8, km, km * 5.5, run ? runLoad : null));
        }
        return out;
    }

    private void seed(long athlete, LocalDate first) {
        year().forEach((i, d) -> {
            LocalDate date = first.plusDays(i);
            jdbc.sql("""
                    INSERT INTO day_features (athlete_id, cycle_date, feature_version, day_load_edwards, day_load_banister, run_km, has_garmin_core)
                    VALUES (?, ?, 'f3', ?, ?, ?, true)""").params(athlete, date, d.total(), d.banister(), d.runKm()).update();
            if (d.runKm() > 0) {
                long act = jdbc.sql("""
                        INSERT INTO activity (athlete_id, source, source_id, sport, start_at, calendar_date, duration_s, distance_m)
                        VALUES (?, 'GARMIN', ?, 'running', ?::timestamptz, ?, ?, ?) RETURNING id""")
                        .params(athlete, "run-" + athlete + "-" + i, date + "T07:00:00Z", date, (int) Math.round(d.runMin() * 60), d.runKm() * 1000)
                        .query(Long.class).single();
                jdbc.sql("""
                        INSERT INTO activity_load (activity_id, athlete_id, trimp_edwards, trimp_banister, method, feature_version)
                        VALUES (?, ?, ?, ?, 'stream', 'f3')""").params(act, athlete, d.runLoad(), d.runLoad() * 0.8).update();
            }
        });
    }

    private LocalDate firstDay(long athlete) {
        return signals.today(athlete).minusDays(DAYS - 1);
    }

    @Test
    void aSyntheticYear() {
        long a = newAthlete("Signals year");
        LocalDate first = firstDay(a);
        seed(a, first);

        var o = signals.recompute(a);
        assertThat(o.days()).isEqualTo(DAYS);
        assertThat(o.from()).isEqualTo(first);
        assertThat(o.settingsVersion()).isEqualTo(settings.current().id());
        assertThat(count("SELECT count(*) FROM day_signals WHERE athlete_id = ? AND signals_version = 's2'", a)).isEqualTo(DAYS);

        var stored = new HashMap<LocalDate, com.fasterxml.jackson.databind.JsonNode>();
        signals.read(a, first, first.plusDays(DAYS - 1)).forEach(s -> stored.put(s.date(), s.signals()));

        // The EWMA is the same as on the Trends screen, over the stored daily loads.
        Double[] total = new Double[DAYS];
        var y = year();
        for (int i = 0; i < DAYS; i++) total[i] = y.containsKey(i) ? y.get(i).total() : null;
        Double[] acute = Ewma.of(total, 7);
        Double[] chronic = Ewma.of(total, 28);
        var lastDay = stored.get(first.plusDays(DAYS - 1)).get("load");
        assertThat(lastDay.get("method").asText()).isEqualTo("edwards");
        assertThat(lastDay.get("acute").asDouble()).isCloseTo(acute[DAYS - 1], within(0.05));
        assertThat(lastDay.get("chronic").asDouble()).isCloseTo(chronic[DAYS - 1], within(0.05));

        // Watch off: no load, no average on those days, and a rest day elsewhere is a real 0.
        var gapDay = stored.get(first.plusDays(GAP_FROM + 3)).get("load");
        assertThat(gapDay.get("daily")).isNull();
        assertThat(gapDay.get("acwr")).isNull();
        assertThat(stored.get(first.plusDays(1)).get("load").get("daily").asDouble()).isEqualTo(0.0);

        // The hard block lifts the ratio, total and running-only, above anything in the steady months; the taper
        // brings it below 1. (Daily values swing with the weekly pattern, so the block is judged by its peak.)
        java.util.function.BiFunction<Integer, String, Double> acwr = (i, part) -> {
            var load = stored.get(first.plusDays(i)).get("load");
            return ("running".equals(part) ? load.get("running") : load).get("acwr").asDouble();
        };
        double steadyMax = 0, hardMax = 0, hardRunMax = 0;
        for (int i = 200; i < HARD_FROM; i++) steadyMax = Math.max(steadyMax, acwr.apply(i, "total"));
        for (int i = HARD_FROM; i < HARD_FROM + 10; i++) {
            hardMax = Math.max(hardMax, acwr.apply(i, "total"));
            hardRunMax = Math.max(hardRunMax, acwr.apply(i, "running"));
        }
        assertThat(acwr.apply(HARD_FROM - 1, "total")).isCloseTo(1.0, within(0.15));
        assertThat(hardMax).isGreaterThan(1.4).isGreaterThan(steadyMax);
        assertThat(hardRunMax).isGreaterThan(1.4);
        assertThat(acwr.apply(TAPER_FROM + 10, "total")).isLessThan(0.95);

        // Running volume: an ordinary week has 4 runs, 10+10+10+20 km.
        var running = stored.get(first.plusDays(90)).get("running");
        assertThat(running.get("shortKm").asDouble()).isEqualTo(50.0);
        assertThat(running.get("longKm").asDouble()).isEqualTo(150.0);
        assertThat(running.get("longWeeklyAvgKm").asDouble()).isEqualTo(50.0);
        assertThat(running.get("shortMinutes").asDouble()).isEqualTo(275.0);

        // Foster: an ordinary week of runs, a bike day and a rest day.
        var foster = stored.get(first.plusDays(90)).get("foster");
        assertThat(foster.get("knownDays").asInt()).isEqualTo(7);
        assertThat(foster.get("weekLoad").asDouble()).isEqualTo(530.0);
        assertThat(foster.get("monotony").asDouble()).isBetween(1.0, 3.0);
    }

    @Test
    void aSettingsChangeAddsRowsUnderTheNewVersionAndKeepsTheOldOnes() {
        long a = newAthlete("Signals settings");
        seed(a, firstDay(a));
        long before = signals.recompute(a).settingsVersion();
        double edwardsAcute = signals.recompute(a).latest().load().load().acute();

        ObjectNode changed = settings.current().settings().deepCopy();
        ((ObjectNode) changed.get("signals").get("load")).put("dailyLoad", "banister");
        settingsRepo.insert(changed, "test: Banister daily load", null);
        try {
            var o = signals.recompute(a);
            assertThat(o.settingsVersion()).isGreaterThan(before);
            assertThat(o.latest().load().load().method()).isEqualTo("banister");
            assertThat(o.latest().load().load().acute()).isCloseTo(edwardsAcute * 0.8, within(0.2));
            assertThat(count("SELECT count(*) FROM day_signals WHERE athlete_id = ? AND settings_version = ?", a, before))
                    .as("rows of the older settings version stay, for suggestions that name it").isEqualTo(DAYS);
            assertThat(count("SELECT count(*) FROM day_signals WHERE athlete_id = ?", a)).isEqualTo(2L * DAYS);
        } finally {
            ObjectNode back = settings.current().settings().deepCopy();
            ((ObjectNode) back.get("signals").get("load")).put("dailyLoad", "edwards");
            settingsRepo.insert(back, "test: back to Edwards", null);
        }
    }

    @Test
    void aRecomputeReplacesTheRowsOfTheSameVersions() {
        long a = newAthlete("Signals rerun");
        seed(a, firstDay(a));
        signals.recompute(a);
        signals.recompute(a);
        assertThat(count("SELECT count(*) FROM day_signals WHERE athlete_id = ?", a)).isEqualTo(DAYS);
    }

    @Test
    void anAthleteWithoutFeaturesHasNoSignals() {
        long a = newAthlete("Signals none");
        var o = signals.recompute(a);
        assertThat(o.days()).isZero();
        assertThat(o.latest()).isNull();
    }

    @Test
    void readingFollowsAccessAndRecomputingIsCoachOnly() throws Exception {
        long a = newAthlete("Signals access");
        long other = newAthlete("Signals other");
        seed(a, firstDay(a));
        CurrentUser coach = users.findById(users.insert("coach-sig-" + System.nanoTime(), "x", Role.COACH, null)).orElseThrow();
        CurrentUser athlete = users.findById(users.insert("ath-sig-" + System.nanoTime(), "x", Role.ATHLETE, a)).orElseThrow();

        mvc.perform(post("/api/athletes/{id}/signals/recompute", a).with(user(athlete)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/athletes/{id}/signals/recompute", a).with(user(coach)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days").value(DAYS));
        mvc.perform(get("/api/athletes/{id}/signals", a).with(user(athlete)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(28))
                .andExpect(jsonPath("$[27].signals.load.method").value("edwards"));
        mvc.perform(get("/api/athletes/{id}/signals", other).with(user(athlete))).andExpect(status().isForbidden());
    }

    private void scoresFrom(long athlete, LocalDate first, int fromIndex) {
        jdbc.sql("""
                INSERT INTO model_version (athlete_id, version, status, feature_version, coefficients, metrics, passed, report_html)
                VALUES (?, ?, 'ACTIVE', 'f3', '{}'::jsonb, '{}', true, '')""").params(athlete, "m-sig-" + athlete).update();
        jdbc.sql("UPDATE day_features SET rmssd = 55, sleep_minutes = 420 WHERE athlete_id = ? AND cycle_date >= ?")
                .params(athlete, first.plusDays(fromIndex)).update();
        for (int i = fromIndex; i < DAYS; i++) {
            if (!year().containsKey(i)) continue;
            boolean lastThree = i >= DAYS - 3;
            jdbc.sql("""
                    INSERT INTO derived_day_metrics (athlete_id, cycle_date, model_version, feature_version, recovery_pct, recovery_band,
                        strain, sleep_performance_pct, sleep_need_min)
                    VALUES (?, ?, ?, 'f3', ?, ?, 10, 88, 480)""")
                    .params(athlete, first.plusDays(i), "m-sig-" + athlete, lastThree ? 50 : 70, lastThree ? "YELLOW" : "GREEN").update();
        }
    }

    @Test
    void readinessSleepCheckinsSessionsAndToleranceOverTheYear() {
        long a = newAthlete("Signals all");
        LocalDate first = firstDay(a);
        LocalDate today = first.plusDays(DAYS - 1);
        seed(a, first);
        scoresFrom(a, first, 200);
        jdbc.sql("INSERT INTO subjective_log (athlete_id, log_date, niggle_location, niggle_severity) VALUES (?, ?, 'calf', 2)")
                .params(a, today.minusDays(2)).update();
        jdbc.sql("INSERT INTO planned_session (athlete_id, session_date, session_type, demand, est_strain, title) VALUES (?, ?, 'threshold', 'KEY', 14.5, 'Tempo')")
                .params(a, today).update();
        jdbc.sql("INSERT INTO planned_session (athlete_id, session_date, session_type, demand, status, title) VALUES (?, ?, 'easy', 'EASY', 'SKIPPED', 'Easy')")
                .params(a, today).update();

        var o = signals.recompute(a);
        var day = o.latest();
        assertThat(day.date()).isEqualTo(today);

        // Readiness: three yellow days after green ones; nothing before the first HRV day.
        assertThat(day.readiness().band()).isEqualTo("YELLOW");
        assertThat(day.readiness().nonGreenStreakDays()).isEqualTo(3);
        assertThat(day.readiness().trendShort()).isCloseTo(50 - (50 + 50 + 70) / 3.0, within(0.05));
        var stored = new HashMap<LocalDate, com.fasterxml.jackson.databind.JsonNode>();
        signals.read(a, first, today).forEach(r -> stored.put(r.date(), r.signals()));
        assertThat(stored.get(first.plusDays(150)).get("readiness").get("beforeHrv").asBoolean()).isTrue();

        // Sleep: 7 nights of 420 min against a need of 480.
        assertThat(day.sleep().debtMin()).isEqualTo(420);
        assertThat(day.sleep().nightsOfNeed()).isEqualTo(0.88);

        // Check-ins: the calf niggle reported two days ago is in its third day.
        assertThat(day.subjective().niggles()).singleElement().satisfies(n -> assertThat(n.persistDays()).isEqualTo(3));

        // Session demand: the skipped session doesn't count.
        assertThat(day.sessions().maxDemand()).isEqualTo("KEY");
        assertThat(day.sessions().planned()).hasSize(1);

        // Tolerance: the peak is the hard block (3 weeks at 90 km + 1 ordinary week ≈ 80 km/week), well before the
        // niggle; the taper is time away and the chronic running volume caps it.
        var t = day.tolerance();
        assertThat(t.source()).isEqualTo("computed");
        assertThat(t.peakKmPerWeek()).isBetween(75.0, 85.0);
        assertThat(t.peakTo()).isBefore(today.minusDays(2));
        assertThat(t.flaggedWindows()).isPositive();
        assertThat(t.interruptedWeeks()).isPositive();
        assertThat(t.kmPerWeek()).isEqualTo(t.computedKmPerWeek()).isLessThan(t.peakKmPerWeek());
        if (t.capped()) assertThat(t.kmPerWeek()).isEqualTo(t.capKmPerWeek());
        else assertThat(t.kmPerWeek()).isEqualTo(t.decayedKmPerWeek());
    }

    @Test
    void theCoachSetsAndClearsATolerOverrideAndACheckinRecomputes() throws Exception {
        long a = newAthlete("Signals override");
        seed(a, firstDay(a));
        signals.recompute(a);
        CurrentUser coach = users.findById(users.insert("coach-tol-" + System.nanoTime(), "x", Role.COACH, null)).orElseThrow();
        CurrentUser athlete = users.findById(users.insert("ath-tol-" + System.nanoTime(), "x", Role.ATHLETE, a)).orElseThrow();

        mvc.perform(post("/api/athletes/{id}/tolerance/override", a).with(user(athlete)).with(csrf())
                        .contentType("application/json").content("{\"kmPerWeek\": 45}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/athletes/{id}/tolerance/override", a).with(user(coach)).with(csrf())
                        .contentType("application/json").content("{\"kmPerWeek\": 2000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/athletes/{id}/tolerance/override", a).with(user(coach)).with(csrf())
                        .contentType("application/json").content("{\"kmPerWeek\": 45, \"note\": \"old calf trouble\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tolerance.kmPerWeek").value(45.0))
                .andExpect(jsonPath("$.tolerance.source").value("override"))
                .andExpect(jsonPath("$.tolerance.computedKmPerWeek").isNumber())
                .andExpect(jsonPath("$.overrides[0].note").value("old calf trouble"));
        mvc.perform(get("/api/athletes/{id}/tolerance", a).with(user(athlete)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tolerance.kmPerWeek").value(45.0));
        mvc.perform(post("/api/athletes/{id}/tolerance/override", a).with(user(coach)).with(csrf())
                        .contentType("application/json").content("{\"kmPerWeek\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tolerance.source").value("computed"))
                .andExpect(jsonPath("$.overrides.length()").value(2));
        assertThat(count("SELECT count(*) FROM tolerance_override WHERE athlete_id = ?", a)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.sql("DELETE FROM tolerance_override WHERE athlete_id = ?").param(a).update()).hasMessageContaining("append-only");

        // A check-in through the API recomputes today's flags.
        mvc.perform(post("/api/athletes/{id}/checkins", a).with(user(athlete)).with(csrf())
                        .contentType("application/json").content("{\"illness\": true}"))
                .andExpect(status().isCreated());
        LocalDate today = signals.today(a);
        var todays = signals.read(a, today, today);
        assertThat(todays).singleElement().satisfies(r -> assertThat(r.signals().get("subjective").get("illness").asBoolean()).isTrue());
    }
}
