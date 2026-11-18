package app.training.adaptation.checkin;

import java.io.PrintStream;
import java.util.function.Function;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line seed of the check-in history from Garmin's effort fields (ADR 0020 amendment):
 *
 * <pre>
 *   docker compose run --rm app garmin-seed --athlete 1
 * </pre>
 *
 * Runs without the web server, prints what it did and exits. Exit codes: 0 done, 2 unknown athlete,
 * 64 bad arguments. Running it again adds nothing.
 */
public final class GarminSeedCli {
    public static final String COMMAND = "garmin-seed";
    static final String USAGE = "Usage: garmin-seed --athlete <id>";

    private GarminSeedCli() {}

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
                throw new IllegalArgumentException("Bad value for " + name + ": " + value);
            }
        }
        if (athlete == null) throw new IllegalArgumentException("--athlete is required");
        return athlete;
    }

    static int run(String[] args, Function<String[], ConfigurableApplicationContext> context, PrintStream out, PrintStream err) {
        long athlete;
        try {
            athlete = parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 64;
        }
        try (var ctx = context.apply(new String[] {"--app.command-line=true", "--spring.main.banner-mode=off"})) {
            var jdbc = ctx.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
            boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM athlete WHERE id = ?)").param(athlete).query(Boolean.class).single();
            if (!known) {
                err.println("No athlete with id " + athlete);
                return 2;
            }
            out.println(summary(ctx.getBean(GarminSeedService.class).seed(athlete)));
            return 0;
        }
    }

    static String summary(GarminSeedService.Outcome o) {
        return "Garmin effort found on " + o.activitiesWithEffort() + " activities: seeded " + o.seeded()
                + " new check-ins, " + o.alreadyChecked() + " already had a check-in (left as they are), "
                + o.noUsableRpe() + " had no usable RPE."
                + (o.activitiesWithEffort() == 0
                        ? " If you expected some, upload your Garmin export again on the Imports page first." : "");
    }

    public static int main(Class<?> app, String[] args) {
        return run(args, springArgs -> new SpringApplicationBuilder(app).web(WebApplicationType.NONE).logStartupInfo(false).run(springArgs),
                System.out, System.err);
    }
}
