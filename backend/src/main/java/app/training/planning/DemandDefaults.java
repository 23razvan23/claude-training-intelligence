package app.training.planning;

import app.training.adaptation.settings.SettingsCatalog;
import app.training.adaptation.settings.SettingsService;
import app.training.planning.SessionTypes.Demand;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * The demand a new session starts with, per type, from the current settings version ({@code planning.defaultDemand.*},
 * ADR 0019 D3, ADR 0021). A type the store doesn't know, or an unreadable value, falls back to EASY, the least
 * protective demand, which the coach can change on the session.
 */
@Component
public class DemandDefaults {
    private final SettingsService settings;

    public DemandDefaults(SettingsService settings) {
        this.settings = settings;
    }

    public Function<String, Demand> current() {
        var s = settings.current();
        return code -> {
            var n = SettingsCatalog.at(s.settings(), "planning.defaultDemand." + code);
            if (n == null || !n.isTextual()) return Demand.EASY;
            try {
                return Demand.valueOf(n.asText());
            } catch (IllegalArgumentException e) {
                return Demand.EASY;
            }
        };
    }
}
