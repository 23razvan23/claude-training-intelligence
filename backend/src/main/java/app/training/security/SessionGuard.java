package app.training.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * R-09: a session carries a copy of the user, so each request checks the database again. The
 * request is answered 401 and the session ended when the user is gone, disabled, or changed the
 * password after this session logged in (ADR 0005). Never logs request data.
 */
public class SessionGuard extends OncePerRequestFilter {
    /** Session attribute: when this session's password was last proven (login or password change). */
    static final String LOGIN_AT = "app.training.loginAt";

    private final UserRepository users;

    public SessionGuard(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser principal && !valid(request, principal)) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean valid(HttpServletRequest request, CurrentUser principal) {
        var state = users.credentialState(principal.id());
        if (state.isEmpty() || !state.get().enabled()) return false;
        HttpSession session = request.getSession(false);
        // No login time: a session from before V18 (it only gets the exists/enabled check), or a
        // request that carries no session at all.
        if (session == null || !(session.getAttribute(LOGIN_AT) instanceof Instant loginAt)) return true;
        Instant changed = state.get().passwordChangedAt();
        return changed == null || !changed.isAfter(loginAt);
    }
}
