package app.training.security;

import app.training.athletes.AthleteRepository;
import app.training.athletes.AthleteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the coach login (and the coach's own athlete profile) on first start,
 * from COACH_USERNAME / COACH_PASSWORD. Does nothing once a coach exists.
 */
@Component
public class BootstrapCoach implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapCoach.class);

    private final BootstrapProperties props;
    private final UserRepository users;
    private final AthleteRepository athletes;
    private final PasswordEncoder encoder;

    public BootstrapCoach(BootstrapProperties props, UserRepository users, AthleteRepository athletes, PasswordEncoder encoder) {
        this.props = props;
        this.users = users;
        this.athletes = athletes;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.anyCoach()) return;
        if (isBlank(props.coachUsername()) || isBlank(props.coachPassword())) {
            log.warn("No coach account exists and COACH_USERNAME / COACH_PASSWORD are not set; nobody can log in.");
            return;
        }
        String name = isBlank(props.coachDisplayName()) ? props.coachUsername() : props.coachDisplayName();
        long athleteId = athletes.insert(new AthleteRequest(name, null, null, null, null, null, null, null, null, null, null));
        users.insert(props.coachUsername(), encoder.encode(props.coachPassword()), Role.COACH, athleteId);
        log.info("Created coach account '{}' with their own athlete profile.", props.coachUsername());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
