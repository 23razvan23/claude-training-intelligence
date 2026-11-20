package app.training.adaptation.engine;

import app.training.adaptation.engine.Model.Reason;
import java.io.PrintStream;
import java.util.function.Function;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line run of the rules engine (stage 2-3, ADR 0022), like {@code signals}: recomputes the signals, runs the
 * engine for today and prints the pending suggestions.
 *
 * <pre>
 *   docker compose run --rm app suggest --athlete 1
 * </pre>
 *
 * Exit codes: 0 done, 2 unknown athlete, 64 bad arguments.
 */
public final class SuggestCli {
    public static final String COMMAND = "suggest";
    static final String USAGE = "Usage: suggest --athlete <id>";

    private SuggestCli() {}

    static long parse(String[] args) {
        Long athlete = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            String name = a, value;
            int eq = a.indexOf('=');
            if (eq > 0) {
                name = a.substring(0, eq);
                value = a.substring(eq + 1);
            } else {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                value = args[++i];
            }
            if (!"--athlete".equals(name)) throw new IllegalArgumentException("Unknown option " + name);
            try {
                athlete = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad value for --athlete: " + value);
            }
        }
        if (athlete == null) throw new IllegalArgumentException("--athlete is required");
        return athlete;
    }

    static int run(String[] args, Function<String[], ConfigurableApplicationContext> context, PrintStream out, PrintStream err) {
        long athleteId;
        try {
            athleteId = parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 64;
        }
        try (var ctx = context.apply(new String[] {"--app.command-line=true", "--spring.main.banner-mode=off"})) {
            out.print(summary(ctx.getBean(SuggestionService.class).runWithFreshSignals(athleteId)));
            return 0;
        } catch (app.training.common.ApiException e) {
            err.println("No suggestions: " + e.getMessage());
            return 2;
        }
    }

    static String summary(SuggestionService.RunOutcome o) {
        var b = new StringBuilder();
        b.append("Rules ").append(o.rulesVersion()).append(" (settings version ").append(o.settingsVersion()).append(", signals ")
                .append(o.signalsVersion()).append(") on ").append(o.engineRunDate()).append(": ").append(o.created()).append(" new, ")
                .append(o.kept()).append(" unchanged, ").append(o.expired()).append(" expired; ").append(o.pending().size()).append(" pending.\n");
        for (var s : o.pending()) {
            b.append("- ").append(s.forDate()).append(" ").append(s.action()).append(" (").append(s.confidence()).append(", session ")
                    .append(s.plannedSessionId()).append(s.counterpartSessionId() == null ? "" : " and " + s.counterpartSessionId()).append(")\n");
            for (var r : s.reasons()) b.append("    ").append(r.path("text").asText()).append('\n');
        }
        for (Reason h : o.healthMessages()) b.append("Health: ").append(h.text()).append('\n');
        for (String n : o.notes()) b.append("Note: ").append(n).append('\n');
        return b.toString();
    }

    public static int main(Class<?> app, String[] args) {
        return run(args, springArgs -> new SpringApplicationBuilder(app).web(WebApplicationType.NONE).logStartupInfo(false).run(springArgs),
                System.out, System.err);
    }
}
