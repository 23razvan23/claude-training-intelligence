package app.training.garmin;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Garmin connection, coach only (ADR 0011 D3). Secrets travel only in request bodies; the
 * request records hide them from {@code toString()} (D9).
 */
@RestController
@RequestMapping("/api/athletes/{athleteId}/garmin")
@PreAuthorize("hasRole('COACH')")
public class GarminController {

    public record ConnectRequest(@NotBlank @Size(max = 320) String username, @NotBlank @Size(max = 1024) String password) {
        @Override
        public String toString() {
            return "ConnectRequest[***]";
        }
    }

    public record MfaRequest(@NotBlank @Size(max = 200) String mfaId, @NotBlank @Size(max = 32) String code) {
        @Override
        public String toString() {
            return "MfaRequest[***]";
        }
    }

    private final GarminConnectionService garmin;
    private final AccessPolicy access;
    private final AthleteRepository athletes;

    public GarminController(GarminConnectionService garmin, AccessPolicy access, AthleteRepository athletes) {
        this.garmin = garmin;
        this.access = access;
        this.athletes = athletes;
    }

    @GetMapping
    public GarminConnectionService.Status status(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        requireAthlete(user, athleteId);
        return garmin.status(athleteId);
    }

    /** Signs in once; answers CONNECTED, or MFA_REQUIRED with an id to send to /mfa with the code. */
    @PostMapping("/connect")
    public GarminConnectionService.ConnectResult connect(@PathVariable long athleteId, @Valid @RequestBody ConnectRequest req,
                                                         @AuthenticationPrincipal CurrentUser user) {
        requireAthlete(user, athleteId);
        return garmin.connect(athleteId, req.username().strip(), req.password());
    }

    @PostMapping("/mfa")
    public GarminConnectionService.ConnectResult mfa(@PathVariable long athleteId, @Valid @RequestBody MfaRequest req,
                                                     @AuthenticationPrincipal CurrentUser user) {
        requireAthlete(user, athleteId);
        return garmin.mfa(athleteId, req.mfaId(), req.code().strip());
    }

    /** Deletes the stored token (ADR 0011 D7). Works even while sync is not configured. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        requireAthlete(user, athleteId);
        garmin.disconnect(athleteId);
    }

    private void requireAthlete(CurrentUser user, long athleteId) {
        access.requireAthlete(user, athleteId);
        athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
    }
}
