package app.training.adaptation.settings;

import app.training.planning.SessionTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every [configurable] value of the adaptive engine, with its default and a one-line rationale (stage 2-2, ADR 0021).
 * The defaults are starting values, not settled science. They are saved as settings version 1; code reads the
 * current version from the store ({@link SettingsService}), never these defaults directly. A value added to this
 * list later is added to the store as a new version on the next start, so every number stays versioned.
 *
 * <p>Settings are coach-wide: one methodology for all athletes (coach, 2026-11-18). Per-athlete values, such as a
 * running-tolerance override, live with the athlete.
 */
public final class SettingsCatalog {
    private SettingsCatalog() {}

    /** One setting: a dotted path in the settings document, its default and why. */
    public record Setting(String path, Object defaultValue, String rationale) {}

    private static final List<Setting> ALL;

    static {
        var s = new ArrayList<Setting>();
        // Daily load used by acute/chronic load, ACWR and Foster (ADR 0018 D9).
        s.add(new Setting("signals.load.dailyLoad", "edwards",
                "Edwards daily TRIMP, the load the Trends screen and the strain model use, so both agree; 'banister' is the alternative."));
        s.add(new Setting("signals.load.acuteSpanDays", 7,
                "About one training week; a conventional EWMA span in load monitoring, not a proven threshold."));
        s.add(new Setting("signals.load.chronicSpanDays", 28,
                "About one training block (4 weeks); conventional, not a proven threshold."));
        // Foster monotony and strain.
        s.add(new Setting("signals.foster.windowDays", 7, "Foster defined monotony and strain over one training week."));
        s.add(new Setting("signals.foster.minKnownDays", 5,
                "With fewer recorded days in the week the spread of daily loads means little, so no monotony is given."));
        // Running volume.
        s.add(new Setting("signals.running.shortWindowDays", 7, "The current week of running (spec: 7-day running volume)."));
        s.add(new Setting("signals.running.longWindowDays", 21, "The last three weeks of running (spec: 3-week running volume)."));
        // Readiness (Module 1 recovery) trend.
        s.add(new Setting("signals.readiness.trendShortDays", 3, "Spec: 3-day recovery trend; a few nights smooth out one odd night."));
        s.add(new Setting("signals.readiness.trendLongDays", 7, "Spec: 7-day recovery trend; about one training week."));
        // Sleep debt.
        s.add(new Setting("signals.sleep.debtWindowDays", 7, "Spec: accumulated shortfall over the last 7 days."));
        s.add(new Setting("signals.sleep.surplusPaysBack", true,
                "A longer night pays back an earlier short one; the debt never goes below 0 (coach, 2026-11-18)."));
        // Check-in flags.
        s.add(new Setting("signals.subjective.niggleStaleDays", 7,
                "A niggle not reported for a week is taken as gone even if nobody said so; a heuristic, the athlete can mark it gone sooner."));
        // Running tolerance (SPEC.md Module 3), in km per week (coach, 2026-11-18).
        s.add(new Setting("signals.tolerance.lookbackDays", 365, "Spec: the last 12 months."));
        s.add(new Setting("signals.tolerance.windowWeeks", 4, "Spec: the highest 4-week average of weekly running volume."));
        s.add(new Setting("signals.tolerance.interruptedWeekShare", 0.5,
                "A week with less than half the peak weekly average counts as interrupted training (coach, 2026-11-18); a heuristic."));
        s.add(new Setting("signals.tolerance.decayPerWeek", 0.10, "Spec example: -10% per week of interrupted training."));
        s.add(new Setting("signals.tolerance.graceWeeks", 1, "Spec: the decay starts after one interrupted week."));
        s.add(new Setting("signals.tolerance.returnWeeks", 2,
                "Only the current break decays the tolerance; two normal weeks in a row end a break (coach, 2026-11-19); a heuristic."));
        s.add(new Setting("signals.tolerance.chronicCapFactor", 1.0,
                "Spec: tolerance is capped by the chronic running volume; 1.0 = exactly that, a higher value leaves room above it."));
        // Rules engine (stage 2-3, ADR 0022). Starting values, not settled science; each is a coaching heuristic unless the spec names it.
        s.add(new Setting("engine.horizonDays", 7, "Spec: every morning the engine reviews the next 7 days."));
        s.add(new Setting("engine.red.swapWindowDays", 3,
                "Red day: swap the hard session with an easy one at most 3 days later, so it stays close to where the coach put it (ADR 0022 Q7)."));
        s.add(new Setting("engine.replace.easyShare", 0.6,
                "An easy session that replaces a hard one is 60% of its distance and time: clearly easier, but keeps the routine (heuristic)."));
        s.add(new Setting("engine.yellow.streakDays", 2, "Spec: recovery yellow 2+ days in a row."));
        s.add(new Setting("engine.reduce.share", 0.25,
                "Spec: reduce reps or time at intensity by 20-30%; 25% is the middle. Distance and time are cut by this share (ADR 0022 Q4)."));
        s.add(new Setting("engine.volume.toleranceMargin", 0.10, "Spec: running volume above tolerance + 10%."));
        s.add(new Setting("engine.volume.acwrThreshold", 1.3,
                "Spec: running ACWR above 1.3. One signal among several, never a gate on its own: alone it gives a low-confidence suggestion."));
        s.add(new Setting("engine.volume.maxEasyCutShare", 0.3,
                "Trim each easy run by at most 30%, so it is still worth doing; then shorten the long run (spec order)."));
        s.add(new Setting("engine.volume.maxLongCutShare", 0.2,
                "Shorten the long run by at most 20%; it is a key session, so it is cut last and least (heuristic)."));
        s.add(new Setting("engine.volume.acwrOnlyEasyCutShare", 0.2,
                "When only the running ACWR is high, easy runs are trimmed by 20%: a light touch for a disputed signal (heuristic)."));
        s.add(new Setting("engine.sleep.debtNights", 1.0, "Spec: sleep debt above one night's need."));
        s.add(new Setting("engine.sleep.minKnownNights", 5,
                "Sleep debt from fewer than 5 recorded nights out of 7 gives a medium-confidence suggestion only (heuristic)."));
        s.add(new Setting("engine.niggle.healthMessageDays", 3, "Spec: a health message if a niggle persists 3+ days."));
        s.add(new Setting("engine.illness.activeDays", 2,
                "Illness counts as current when reported today or yesterday: athletes do not check in every day (ADR 0022 Q8; heuristic)."));
        s.add(new Setting("engine.illness.returnDays", 2, "Spec: a graded return over 2-3 days; day 1 easy, day 2 reduced (ADR 0022 Q8)."));
        s.add(new Setting("engine.restore.weeks", 2, "Spec: load well under plan for 2+ weeks."));
        s.add(new Setting("engine.restore.underShare", 0.8,
                "'Well under plan' = less than 80% of the planned running km in each of those weeks (ADR 0022 Q6; heuristic)."));
        s.add(new Setting("engine.restore.stepShare", 0.10,
                "One progression step = 10% of the coming week's planned km, the common '10% rule' of thumb, never above tolerance (ADR 0022 Q6)."));
        s.add(new Setting("engine.raceWeekDays", 7, "Race week = the 7 days ending on race day; it gets only reductions (spec guardrail)."));
        // Planning: the demand a new session starts with, per type (ADR 0019 D3).
        for (var t : SessionTypes.all()) {
            s.add(new Setting("planning.defaultDemand." + t.code(), t.defaultDemand().name(),
                    "Long runs, threshold, VO2max, race pace and races are the sessions a week is built around (KEY); hills SUPPORTING; "
                            + "the rest EASY. A coaching convention, not a measured fact (ADR 0019 D3)."));
        }
        ALL = Collections.unmodifiableList(s);
    }

    public static List<Setting> all() {
        return ALL;
    }

    /** The defaults as one settings document. */
    public static ObjectNode defaults(ObjectMapper json) {
        ObjectNode doc = json.createObjectNode();
        for (var s : ALL) put(doc, s.path(), json.valueToTree(s.defaultValue()));
        return doc;
    }

    /**
     * Sets every catalog value that {@code doc} lacks to its default, in place; stored values are kept, and so are
     * values the catalog no longer knows. Returns the paths that were added (empty: nothing changed).
     */
    public static List<String> fillMissing(ObjectNode doc, ObjectMapper json) {
        var added = new ArrayList<String>();
        for (var s : ALL) {
            if (at(doc, s.path()) == null) {
                put(doc, s.path(), json.valueToTree(s.defaultValue()));
                added.add(s.path());
            }
        }
        return added;
    }

    /** The node at a dotted path, or null. */
    public static JsonNode at(JsonNode doc, String path) {
        JsonNode n = doc;
        for (String part : path.split("\\.")) {
            if (n == null || !n.isObject()) return null;
            n = n.get(part);
        }
        return n == null || n.isNull() || n.isMissingNode() ? null : n;
    }

    private static void put(ObjectNode doc, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode n = doc;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = n.get(parts[i]);
            if (!(child instanceof ObjectNode)) {
                child = n.putObject(parts[i]);
            }
            n = (ObjectNode) child;
        }
        n.set(parts[parts.length - 1], value);
    }
}
