package app.training.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record Me(long id, String username, Role role, Long athleteId) {
        static Me of(CurrentUser u) {
            return new Me(u.id(), u.username(), u.role(), u.athleteId());
        }
    }

    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository;
    private final LoginThrottle throttle;

    public AuthController(
            AuthenticationManager authManager, SecurityContextRepository contextRepository, LoginThrottle throttle) {
        this.authManager = authManager;
        this.contextRepository = contextRepository;
        this.throttle = throttle;
    }

    /** Touch to receive the XSRF-TOKEN cookie before the first state-changing call. */
    @GetMapping("/csrf")
    public void csrf(CsrfToken token) {
        token.getToken();
    }

    @PostMapping("/login")
    public ResponseEntity<Me> login(
            @Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        String key = LoginThrottle.key(req.username(), request.getRemoteAddr());
        long wait = throttle.waitSeconds(key);
        if (wait > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(wait))
                    .build();
        }
        try {
            var auth = authManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password()));
            // Session fixation protection: rotate an existing session id, or start a fresh session.
            if (request.getSession(false) != null) request.changeSessionId();
            else request.getSession(true);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
            request.getSession().setAttribute(SessionGuard.LOGIN_AT, java.time.Instant.now());
            throttle.succeeded(key);
            return ResponseEntity.ok(Me.of((CurrentUser) auth.getPrincipal()));
        } catch (AuthenticationException e) {
            throttle.failed(key);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal CurrentUser user) {
        return Me.of(user);
    }
}
