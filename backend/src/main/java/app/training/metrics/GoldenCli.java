package app.training.metrics;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The golden-file check on the command line (R-14, ADR 0017):
 *
 * <pre>
 *   docker compose run --rm app golden freeze --athlete 1 [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--scores-only]
 *   docker compose run --rm app golden check  --athlete 1 [--file name.json]
 * </pre>
 *
 * Exit codes: 0 nothing differs (freeze: done), 1 code changed, 3 inputs changed, 2 cannot run, 64 bad arguments.
 */
public final class GoldenCli {
    public static final String COMMAND = "golden";
    static final String USAGE = "Usage: golden freeze --athlete <id> [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--scores-only]"
            + System.lineSeparator() + "       golden check --athlete <id> [--file <golden file>]";

    private GoldenCli() {}

    record Options(String action, long athleteId, LocalDate from, LocalDate to, boolean scoresOnly, String file) {}

    static Options parse(String[] args) {
        if (args.length < 2 || !(args[1].equals("freeze") || args[1].equals("check"))) {
            throw new IllegalArgumentException("Say what to do: freeze or check");
        }
        String action = args[1];
        Long athlete = null;
        LocalDate from = null, to = null;
        boolean scoresOnly = false;
        String file = null;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--scores-only")) {
                if (!action.equals("freeze")) throw new IllegalArgumentException("--scores-only belongs to freeze (a check follows its file)");
                scoresOnly = true;
                continue;
            }
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
                    case "--from" -> from = onlyFor(action, "freeze", name, LocalDate.parse(value));
                    case "--to" -> to = onlyFor(action, "freeze", name, LocalDate.parse(value));
                    case "--file" -> file = onlyFor(action, "check", name, value);
                    default -> throw new IllegalArgumentException("Unknown option " + name);
                }
            } catch (NumberFormatException | DateTimeParseException e) {
                throw new IllegalArgumentException("Bad value for " + name + ": " + value);
            }
        }
        if (athlete == null) throw new IllegalArgumentException("--athlete is required");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("--from is after --to");
        return new Options(action, athlete, from, to, scoresOnly, file);
    }

    private static <T> T onlyFor(String action, String allowed, String option, T value) {
        if (!action.equals(allowed)) throw new IllegalArgumentException(option + " belongs to " + allowed);
        return value;
    }

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
            return execute(o, ctx.getBean(GoldenService.class), out, err);
        }
    }

    static int execute(Options o, GoldenService service, PrintStream out, PrintStream err) {
        try {
            if (o.action().equals("freeze")) {
                var r = service.freeze(o.athleteId(), o.from(), o.to(), o.scoresOnly());
                out.println("Frozen " + r.days() + " days, " + r.from() + " to " + r.to() + ", model " + r.modelVersion() + ", features "
                        + r.featureVersion() + " -> " + r.file().getFileName());
                out.println("After every update that touches the scoring or the imports, run: golden check --athlete " + o.athleteId());
                return 0;
            }
            var r = service.check(o.athleteId(), o.file() == null ? null : Path.of(o.file()));
            r.lines().forEach(out::println);
            return r.exit();
        } catch (GoldenService.Refused e) {
            err.println(e.getMessage());
            return e.exit();
        }
    }

    /** Entry point used by {@code TrainingApplication.main}. */
    public static int main(Class<?> app, String[] args) {
        return run(args, springArgs -> new SpringApplicationBuilder(app).web(WebApplicationType.NONE).logStartupInfo(false).run(springArgs),
                System.out, System.err);
    }
}
