package app.training.adaptation.signals;

import java.io.PrintStream;
import java.util.function.Function;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line recompute of the signals (stage 2-2, ADR 0021):
 *
 * <pre>
 *   docker compose run --rm app signals --athlete 1
 * </pre>
 *
 * Exit codes: 0 done, 2 unknown athlete, 64 bad arguments.
 */
public final class SignalsCli {
    public static final String COMMAND = "signals";
    static final String USAGE = "Usage: signals --athlete <id>";

    private SignalsCli() {}

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
            out.println(summary(ctx.getBean(SignalService.class).recompute(athleteId)));
            return 0;
        } catch (app.training.common.ApiException e) {
            err.println("No signals: " + e.getMessage());
            return 2;
        }
    }

    static String summary(SignalService.Outcome o) {
        if (o.days() == 0) return "Signals " + o.signalsVersion() + ": no days with features yet (settings version " + o.settingsVersion() + ").";
        var l = o.latest();
        return "Signals " + o.signalsVersion() + " (settings version " + o.settingsVersion() + "): " + o.days() + " days, " + o.from()
                + " to " + o.to() + ". On " + l.date() + ": acute " + l.load().acute() + ", chronic " + l.load().chronic() + ", ACWR "
                + l.load().acwr() + "; running ACWR " + l.load().running().acwr() + "; running " + l.running().shortKm() + " km in "
                + l.running().shortWindowDays() + " days, " + l.running().longKm() + " km in " + l.running().longWindowDays()
                + " days; monotony " + l.foster().monotony() + ".";
    }

    public static int main(Class<?> app, String[] args) {
        return run(args, springArgs -> new SpringApplicationBuilder(app).web(WebApplicationType.NONE).logStartupInfo(false).run(springArgs),
                System.out, System.err);
    }
}
