package app.training.adaptation.backtest;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line backtest (stage 2-4, ADR 0023):
 *
 * <pre>
 *   docker compose run --rm app backtest --athlete 1 [--from YYYY-MM-DD] [--to YYYY-MM-DD]
 *       [--races YYYY-MM-DD,...] [--hard-weeks YYYY-MM-DD,...] [--cutoff HH:MM] [--settings-version N]
 * </pre>
 *
 * Without --from/--to: the last 12 months up to yesterday. Each run is stored under a new id; nothing live changes.
 * Exit codes: 0 done, 2 unknown athlete or settings version, 64 bad arguments.
 */
public final class BacktestCli {
    public static final String COMMAND = "backtest";
    static final String USAGE = "Usage: backtest --athlete <id> [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--races YYYY-MM-DD,...] "
            + "[--hard-weeks YYYY-MM-DD,...] [--cutoff HH:MM] [--settings-version N]";

    private BacktestCli() {}

    /** The parsed options; null = not given (the service fills in the defaults). */
    record Options(long athlete, LocalDate from, LocalDate to, List<LocalDate> races, List<LocalDate> hardWeeks, LocalTime cutoff,
                   Long settingsVersion) {
        BacktestParams params(BacktestParams defaults) {
            LocalDate t = to != null ? to : defaults.to();
            LocalDate f = from != null ? from : to != null ? t.minusDays(BacktestParams.DEFAULT_DAYS - 1) : defaults.from();
            return new BacktestParams(f, t, cutoff, races, hardWeeks, null, settingsVersion);
        }
    }

    static Options parse(String[] args) {
        Long athlete = null, settings = null;
        LocalDate from = null, to = null;
        LocalTime cutoff = null;
        List<LocalDate> races = List.of(), hard = List.of();
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
                    case "--races" -> races = dates(value);
                    case "--hard-weeks" -> hard = dates(value);
                    case "--cutoff" -> cutoff = LocalTime.parse(value);
                    case "--settings-version" -> settings = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("Unknown option " + name);
                }
            } catch (NumberFormatException | DateTimeParseException e) {
                throw new IllegalArgumentException("Bad value for " + name + ": " + value);
            }
        }
        if (athlete == null) throw new IllegalArgumentException("--athlete is required");
        if (from != null && to != null && to.isBefore(from)) throw new IllegalArgumentException("--to is before --from");
        return new Options(athlete, from, to, races, hard, cutoff, settings);
    }

    private static List<LocalDate> dates(String csv) {
        var out = new ArrayList<LocalDate>();
        for (String s : csv.split(",")) if (!s.isBlank()) out.add(LocalDate.parse(s.trim()));
        return out;
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
            var service = ctx.getBean(BacktestService.class);
            BacktestParams p;
            try {
                p = o.params(service.defaults(o.athlete()));
            } catch (IllegalArgumentException e) {
                err.println(e.getMessage());
                return 64;
            }
            out.println(summary(service.run(o.athlete(), p, null)));
            return 0;
        } catch (app.training.common.ApiException e) {
            err.println("No backtest: " + e.getMessage());
            return 2;
        }
    }

    static String summary(BacktestService.Outcome o) {
        var sb = new StringBuilder();
        sb.append("Backtest run ").append(o.runId()).append(": ").append(o.mornings()).append(" mornings, ").append(o.from()).append(" to ")
                .append(o.to()).append(", settings version ").append(o.settingsVersion()).append(", scores from model ")
                .append(o.modelVersion() == null ? "(none)" : o.modelVersion()).append(".\n");
        sb.append(o.suggestions()).append(" suggestion(s)");
        if (!o.perRule().isEmpty()) {
            sb.append(": ");
            var parts = new ArrayList<String>();
            o.perRule().forEach((rule, n) -> parts.add(rule + " " + n));
            sb.append(String.join(", ", parts));
        }
        sb.append(".\n");
        sb.append("Recovery-based rules could run from ").append(o.firstReadinessMorning() == null ? "no morning (no readiness)" : o.firstReadinessMorning())
                .append(".");
        if (o.lateRevisionMornings() > 0)
            sb.append("\n").append(o.lateRevisionMornings()).append(" morning(s) use a Garmin summary corrected after that morning (see the notes).");
        sb.append("\nNothing in your plan or suggestions changed; the results are stored under run ").append(o.runId()).append(".");
        return sb.toString();
    }

    public static int main(Class<?> app, String[] args) {
        return run(args, springArgs -> new SpringApplicationBuilder(app).web(WebApplicationType.NONE).logStartupInfo(false).run(springArgs),
                System.out, System.err);
    }
}
