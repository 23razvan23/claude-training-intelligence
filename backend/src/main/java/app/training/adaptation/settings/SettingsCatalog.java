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
        s.add(new Setting("signals.tolerance.chronicCapFactor", 1.0,
                "Spec: tolerance is capped by the chronic running volume; 1.0 = exactly that, a higher value leaves room above it."));
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
