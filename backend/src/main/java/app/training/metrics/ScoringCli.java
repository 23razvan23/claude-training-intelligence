package app.training.metrics;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line scoring (SPEC.md: "a CLI command recomputes any date range"):
 *
 * <pre>
 *   docker compose run --rm app score --athlete 1 [--from 2026-11-01] [--to 2026-11-15]
 * </pre>
 *
 * Starts the application without its web server (so it can run next to the running app),
 * rescores through {@link ScoringService}, prints a summary and exits. Exit codes: 0 done,
 * 2 nothing scored (no usable ACTIVE model, unknown athlete), 64 bad arguments.
 */
public final class ScoringCli {
    public static final String COMMAND = "score";
    static final String USAGE = "Usage: score --athlete <id> [--from YYYY-MM-DD] [--to YYYY-MM-DD]";

    private ScoringCli() {}

    record Options(long athleteId, LocalDate from, LocalDate to) {}

    /** Parses "--name value" and "--name=value"; the first argument is the command itself. */
    static Options parse(String[] args) {
        Long athlete = null;
        LocalDate from = null, to = null;
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
            try {
                switch (name) {
                    case "--athlete" -> athlete = Long.parseLong(value);
                    case "--from" -> from = LocalDate.parse(value);
                    case "--to" -> to = LocalDate.parse(value);
                    default -> throw new IllegalArgumentException("Unknown option " + name);
                }
            } catch (NumberFormatException | DateTimeParseException e) {
                throw new IllegalArgumentException("Bad value for " + name + ": " + value);
            }
        }
        if (athlete == null) throw new IllegalArgumentException("--athlete is required");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("--from is after --to");
        return new Options(athlete, from, to);
    }

    /** Runs the command against an application context made by {@code context}; returns the exit code. */
    static int run(String[] args, Function<String[], ConfigurableApplicationContext> context, PrintStream out, PrintStream err) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 64;
        }
        try (var ctx = context.apply(new String[] {"--app.command-line=true", "--spring.main.banner-mode=off"})) {
            var r = ctx.getBean(ScoringService.class).recompute(o.athleteId(), o.from(), o.to());
            out.println(summary(r));
            return 0;
        } catch (app.training.common.ApiException e) {
            // No usable ACTIVE model, or an unknown athlete: a plain message, not a stack trace.
            err.println("No scores: " + e.getMessage());
            return 2;
        }
    }

    static String summary(ScoringService.Outcome r) {
        if (r.days() == 0) {
            return "Model " + r.modelVersion() + ": no days to score in that range (last data: " + r.lastDataDate() + ").";
        }
        return "Model " + r.modelVersion() + " (features " + r.featureVersion() + "): scored " + r.days() + " days, "
                + r.from() + " to " + r.to() + ". Recovery " + r.withRecovery() + ", strain " + r.withStrain()
                + ", sleep performance " + r.withSleepPerformance() + "; lower confidence " + r.lowerConfidence()
                + ", outside training range " + r.withDrift() + ", stale " + r.stale()
                + (r.profileChanged() ? "; PROFILE CHANGED since calibration" : "")
                + ". Last data: " + r.lastDataDate() + ".";
    }

    /** Entry point used by {@code TrainingApplication.main}. */
    public static int main(Class<?> app, String[] args) {
        return run(args, springArgs -> new SpringApplicationBuilder(app).web(WebApplicationType.NONE).logStartupInfo(false).run(springArgs),
                System.out, System.err);
    }
}
