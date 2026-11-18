package app.training;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class TrainingApplication {
    public static void main(String[] args) {
        if (args.length > 0 && app.training.metrics.ScoringCli.COMMAND.equals(args[0])) {
            System.exit(app.training.metrics.ScoringCli.main(TrainingApplication.class, args));
        }
        if (args.length > 0 && app.training.metrics.GoldenCli.COMMAND.equals(args[0])) {
            System.exit(app.training.metrics.GoldenCli.main(TrainingApplication.class, args));
        }
        if (args.length > 0 && app.training.adaptation.checkin.GarminSeedCli.COMMAND.equals(args[0])) {
            System.exit(app.training.adaptation.checkin.GarminSeedCli.main(TrainingApplication.class, args));
        }
        if (args.length > 0 && app.training.adaptation.signals.SignalsCli.COMMAND.equals(args[0])) {
            System.exit(app.training.adaptation.signals.SignalsCli.main(TrainingApplication.class, args));
        }
        SpringApplication.run(TrainingApplication.class, args);
    }
}
