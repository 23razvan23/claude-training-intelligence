package app.training.security;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    public record UserView(long id, String username, Role role, Long athleteId) {
        static UserView of(CurrentUser u) {
            return new UserView(u.id(), u.username(), u.role(), u.athleteId());
        }
    }

    /** Coaches create athlete logins only; there is one coach account per install. */
    public record CreateAthleteUser(
            @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "[A-Za-z0-9._@-]+") String username,
            @NotBlank @Size(min = 10, max = 200) String password,
            @NotNull Long athleteId) {}

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 200) String newPassword) {}

    private final UserRepository users;
    private final AthleteRepository athletes;
    private final PasswordEncoder encoder;

    public UserController(UserRepository users, AthleteRepository athletes, PasswordEncoder encoder) {
        this.users = users;
        this.athletes = athletes;
        this.encoder = encoder;
    }

    @GetMapping
    @PreAuthorize("hasRole('COACH')")
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('COACH')")
    public UserView create(@Valid @RequestBody CreateAthleteUser req) {
        athletes.findById(req.athleteId()).orElseThrow(() -> ApiException.notFound("Athlete"));
        try {
            long id = users.insert(req.username(), encoder.encode(req.password()), Role.ATHLETE, req.athleteId());
            return UserView.of(users.findById(id).orElseThrow());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Username taken, or this athlete already has a login");
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('COACH')")
    public void delete(@PathVariable long id) {
        if (!users.delete(id)) throw ApiException.notFound("Athlete login");
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ChangePassword req) {
        var current = users.findById(user.id()).orElseThrow();
        if (!encoder.matches(req.currentPassword(), current.passwordHash())) {
            throw ApiException.badRequest("Current password is wrong");
        }
        users.updatePassword(user.id(), encoder.encode(req.newPassword()));
    }
}
