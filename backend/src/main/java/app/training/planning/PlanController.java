package app.training.planning;

import app.training.planning.PlanModels.Plan;
import app.training.planning.PlanModels.PlanRequest;
import app.training.planning.PlanModels.Revision;
import app.training.planning.PlanModels.Session;
import app.training.planning.PlanModels.SessionRequest;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Plan screen (stage 2-1a, ADR 0019). Reading: the coach for anyone, an athlete only their own (AccessPolicy;
 * read-only, coach decision). Every change: coach only.
 */
@RestController
@RequestMapping("/api")
public class PlanController {
    private final PlanService plans;
    private final AccessPolicy access;
    private final PlanImportService imports;
    private final DemandDefaults demandDefaults;

    public PlanController(PlanService plans, AccessPolicy access, PlanImportService imports, DemandDefaults demandDefaults) {
        this.plans = plans;
        this.imports = imports;
        this.access = access;
        this.demandDefaults = demandDefaults;
    }

    @GetMapping("/plan/session-types")
    public List<SessionTypes.SessionType> sessionTypes() {
        return SessionTypes.all(demandDefaults.current());
    }

    @GetMapping("/athletes/{athleteId}/plans")
    public List<Plan> plans(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return plans.plans(athleteId);
    }

    @PostMapping("/athletes/{athleteId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('COACH')")
    public Plan createPlan(@PathVariable long athleteId, @RequestBody PlanRequest req) {
        return plans.createPlan(athleteId, req, "MANUAL");
    }

    @PutMapping("/plans/{planId}")
    @PreAuthorize("hasRole('COACH')")
    public Plan updatePlan(@PathVariable long planId, @RequestBody PlanRequest req) {
        return plans.updatePlan(planId, req);
    }

    @GetMapping("/athletes/{athleteId}/sessions")
    public List<Session> sessions(@PathVariable long athleteId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return plans.sessions(athleteId, from, to);
    }

    @PostMapping("/athletes/{athleteId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('COACH')")
    public Session createSession(@PathVariable long athleteId, @RequestBody SessionRequest req,
                                 @AuthenticationPrincipal CurrentUser user) {
        return plans.createSession(athleteId, req, user.id());
    }

    @PutMapping("/sessions/{sessionId}")
    @PreAuthorize("hasRole('COACH')")
    public Session updateSession(@PathVariable long sessionId, @RequestBody SessionRequest req,
                                 @AuthenticationPrincipal CurrentUser user) {
        return plans.updateSession(sessionId, req, user.id());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('COACH')")
    public void deleteSession(@PathVariable long sessionId, @AuthenticationPrincipal CurrentUser user) {
        plans.deleteSession(sessionId, user.id());
    }

    /** Spreadsheet import, step 1: read the file and say what would happen. Saves nothing. */
    @PostMapping("/athletes/{athleteId}/plan-import/preview")
    @PreAuthorize("hasRole('COACH')")
    public PlanImportModels.Preview importPreview(@PathVariable long athleteId, @RequestPart("file") MultipartFile file)
            throws IOException {
        return imports.preview(athleteId, file.getBytes());
    }

    /** Step 2: the same file again; saves every row or none. */
    @PostMapping("/athletes/{athleteId}/plan-import/commit")
    @PreAuthorize("hasRole('COACH')")
    public PlanImportModels.Imported importCommit(@PathVariable long athleteId, @RequestPart("file") MultipartFile file,
                                                   @AuthenticationPrincipal CurrentUser user) throws IOException {
        return imports.commit(athleteId, file.getBytes(), user.id());
    }

    @GetMapping("/sessions/{sessionId}/revisions")
    public List<Revision> revisions(@PathVariable long sessionId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, plans.session(sessionId).athleteId());
        return plans.revisions(sessionId);
    }
}
