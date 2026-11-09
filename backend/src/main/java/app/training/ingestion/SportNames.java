package app.training.ingestion;

import java.util.Locale;

/** Maps source sport names onto the app's small, normalized sport vocabulary. */
public final class SportNames {
    private SportNames() {}

    public static String normalize(String raw) {
        if (raw == null) return "other";
        String s = raw.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (s.contains("run") || s.contains("jog")) return "running";
        if (s.contains("cycl") || s.contains("bik") || s.contains("ride") || s.contains("spin")) return "cycling";
        if (s.contains("strength") || s.contains("weight") || s.contains("functional") || s.contains("crossfit")) return "strength";
        if (s.contains("hik")) return "hiking";
        if (s.contains("walk")) return "walking";
        if (s.contains("swim")) return "swimming";
        if (s.contains("ski")) return "skiing";
        if (s.contains("yoga") || s.contains("pilates") || s.contains("mobility") || s.contains("stretch")) return "mobility";
        return "other";
    }
}
