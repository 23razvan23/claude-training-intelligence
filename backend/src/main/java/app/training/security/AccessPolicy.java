package app.training.security;

import app.training.common.ApiException;
import org.springframework.stereotype.Component;

/** Row-level access: coaches see every athlete, athletes only their own data. */
@Component
public class AccessPolicy {

    public boolean canAccessAthlete(CurrentUser user, long athleteId) {
        if (user == null) return false;
        return user.isCoach() || (user.athleteId() != null && user.athleteId() == athleteId);
    }

    public void requireAthlete(CurrentUser user, long athleteId) {
        if (!canAccessAthlete(user, athleteId)) throw ApiException.forbidden();
    }
}
