package app.training.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The logged-in principal. athleteId is the athlete profile this login belongs to (optional for coaches). */
public record CurrentUser(long id, String username, String passwordHash, Role role, Long athleteId, boolean enabled)
        implements UserDetails {

    private static final long serialVersionUID = 1L;

    public boolean isCoach() {
        return role == Role.COACH;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "CurrentUser[id=" + id + ", username=" + username + ", role=" + role + "]";
    }
}
