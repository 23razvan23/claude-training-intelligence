package app.training.planning;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.planning.PlanModels.Plan;
import app.training.planning.PlanModels.PlanRequest;
import app.training.planning.PlanModels.Revision;
import app.training.planning.PlanModels.Session;
import app.training.planning.PlanModels.SessionRequest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans and planned sessions (stage 2-1a, ADR 0019). Every create, edit (status changes included) and delete of a
 * session writes one planned_session_revision row in the same transaction (ADR 0018 D5).
 */
@Service
public class PlanService {
    static final Set<String> PLAN_STATUSES = Set.of("DRAFT", "ACTIVE", "COMPLETED", "ARCHIVED");
    /** The longest range one list request may ask for; a display limit, not a training number. */
    static final long MAX_RANGE_DAYS = 400;

    private final PlanRepository repo;
    private final AthleteRepository athletes;

    public PlanService(PlanRepository repo, AthleteRepository athletes) {
        this.repo = repo;
        this.athletes = athletes;
    }

    public List<Plan> plans(long athleteId) {
        return repo.plans(athleteId);
    }

    public Plan plan(long planId) {
        return repo.plan(planId).orElseThrow(() -> ApiException.notFound("Plan"));
    }

    /** A new plan is ACTIVE; an athlete has at most one ACTIVE plan (coach decision, ADR 0019 D1). */
    @Transactional
    public Plan createPlan(long athleteId, PlanRequest r, String origin) {
        if (athletes.findById(athleteId).isEmpty()) throw ApiException.notFound("Athlete");
        String name = checkPlan(r);
        if (repo.activePlan(athleteId).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "This athlete already has an active plan. Archive or complete it first.",
                    "ACTIVE_PLAN_EXISTS");
        long id = repo.insertPlan(athleteId, name, origin, r.startDate(), r.endDate());
        return plan(id);
    }

    @Transactional
    public Plan updatePlan(long planId, PlanRequest r) {
        Plan existing = plan(planId);
        String name = checkPlan(r);
        String status = r.status() == null ? existing.status() : r.status();
        if (!PLAN_STATUSES.contains(status)) throw ApiException.badRequest("Unknown plan status '" + status + "'");
        if ("ACTIVE".equals(status) && !"ACTIVE".equals(existing.status())
                && repo.activePlan(existing.athleteId()).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "This athlete already has an active plan.", "ACTIVE_PLAN_EXISTS");
        for (Session s : repo.sessionsOfPlan(planId)) {
            if (s.date().isBefore(r.startDate()) || s.date().isAfter(r.endDate()))
                throw ApiException.badRequest("The plan has a session on " + s.date() + ", outside the new dates");
        }
        repo.updatePlan(planId, name, status, r.startDate(), r.endDate());
        return plan(planId);
    }

    public List<Session> sessions(long athleteId, LocalDate from, LocalDate to) {
        if (from == null || to == null) throw ApiException.badRequest("from and to are required");
        if (to.isBefore(from)) throw ApiException.badRequest("to is before from");
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS)
            throw ApiException.badRequest("At most " + MAX_RANGE_DAYS + " days at a time");
        return repo.sessions(athleteId, from, to);
    }

    public Session session(long sessionId) {
        return repo.session(sessionId).orElseThrow(() -> ApiException.notFound("Session"));
    }

    public List<Revision> revisions(long sessionId) {
        session(sessionId);
        return repo.revisions(sessionId);
    }

    /** Without a plan id the session goes into the athlete's ACTIVE plan. The plan stretches to include the date. */
    @Transactional
    public Session createSession(long athleteId, SessionRequest request, long userId) {
        SessionRequest r = valid(request);
        Plan plan = targetPlan(athleteId, r.planId());
        repo.stretchPlan(plan.id(), r.date());
        long id = repo.insertSession(athleteId, plan.id(), r);
        Session after = session(id);
        repo.insertRevision(athleteId, id, "CREATE", null, after, userId);
        return after;
    }

    @Transactional
    public Session updateSession(long sessionId, SessionRequest request, long userId) {
        Session before = session(sessionId);
        SessionRequest r = valid(request);
        long planId = r.planId() != null ? targetPlan(before.athleteId(), r.planId()).id()
                : before.planId() != null ? before.planId() : targetPlan(before.athleteId(), null).id();
        repo.stretchPlan(planId, r.date());
        repo.updateSession(sessionId, planId, r);
        Session after = session(sessionId);
        repo.insertRevision(before.athleteId(), sessionId, "EDIT", before, after, userId);
        return after;
    }

    /**
     * Deletes the session. Its history goes with it (the revision rows cascade, ADR 0018 D5), so the UI offers
     * Skipped or Replaced first and asks before a delete. The DELETE row is still written, in case the
     * history is later kept after a delete (ADR 0019 D5).
     */
    @Transactional
    public void deleteSession(long sessionId, long userId) {
        Session before = session(sessionId);
        repo.insertRevision(before.athleteId(), sessionId, "DELETE", before, null, userId);
        repo.deleteSession(sessionId);
    }

    private Plan targetPlan(long athleteId, Long planId) {
        if (planId == null)
            return repo.activePlan(athleteId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                    "This athlete has no active plan. Create a plan first.", "NO_ACTIVE_PLAN"));
        Plan p = plan(planId);
        if (p.athleteId() != athleteId) throw ApiException.badRequest("That plan belongs to another athlete");
        return p;
    }

    private static SessionRequest valid(SessionRequest r) {
        var result = SessionValidator.check(r);
        if (!result.ok()) throw ApiException.badRequest(String.join("; ", result.problems()));
        return result.session();
    }

    private static String checkPlan(PlanRequest r) {
        if (r == null || r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Plan name is missing");
        if (r.name().strip().length() > SessionValidator.MAX_TITLE) throw ApiException.badRequest("Plan name is too long");
        if (r.startDate() == null || r.endDate() == null) throw ApiException.badRequest("Plan start and end dates are required");
        if (r.endDate().isBefore(r.startDate())) throw ApiException.badRequest("The plan ends before it starts");
        return r.name().strip();
    }
}
