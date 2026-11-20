package app.training.adaptation.engine;

import app.training.adaptation.engine.SuggestionRepository.Suggestion;
import app.training.common.ApiException;
import app.training.planning.PlanModels.Session;
import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.PlanService;
import app.training.planning.SessionTypes.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The coach's decision on a suggestion (stage 2-3 PR 2, ADR 0022): accept, edit (change the "after" first, then
 * accept) or reject. Accepting changes the plan through {@link PlanService}, so the same checks apply as for a typed
 * session; every changed session gets {@code changed_by_suggestion_id} and a history row naming the suggestion
 * (ADR 0018 D5). A replacement is a new session pointing at the old one, which is set to REPLACED. The decision, who
 * made it and when, and the coach's note are stored on the suggestion; the applied version is in the history rows.
 */
@Service
public class DecisionService {
    static final int MAX_NOTE = 2000;

    private final SuggestionRepository repo;
    private final PlanService plans;
    private final ObjectMapper json;

    public DecisionService(SuggestionRepository repo, PlanService plans, ObjectMapper json) {
        this.repo = repo;
        this.plans = plans;
        this.json = json;
    }

    /** {@code after}: the coach's edited version ({@code {"session": …, "counterpart": …}}), or null to accept as suggested. */
    public record Decision(JsonNode after, String note) {}

    public record Outcome(Suggestion suggestion, List<Session> changed) {}

    public Suggestion find(long id) {
        return repo.find(id).orElseThrow(() -> ApiException.notFound("Suggestion"));
    }

    @Transactional
    public Outcome accept(long id, Decision d, long userId) {
        Suggestion s = pending(id);
        String note = note(d);
        JsonNode after = d == null || d.after() == null || d.after().isNull() ? s.after() : d.after();
        unchanged(s);

        var changed = new ArrayList<Session>();
        SessionRequest main = request(after, "session");
        boolean edited = !same(main, request(s.after(), "session"))
                || ("SWAP".equals(s.action()) && !same(request(after, "counterpart"), request(s.after(), "counterpart")));
        switch (s.action()) {
            case "REPLACE" -> changed.add(plans.replaceSession(s.plannedSessionId(), main, userId, s.id()));
            case "SWAP" -> {
                changed.add(plans.updateSession(s.plannedSessionId(), main, userId, s.id()));
                changed.add(plans.updateSession(s.counterpartSessionId(), request(after, "counterpart"), userId, s.id()));
            }
            default -> changed.add(plans.updateSession(s.plannedSessionId(), main, userId, s.id()));
        }
        if (!repo.decide(s.id(), edited ? "EDITED" : "ACCEPTED", userId, note)) throw alreadyDecided();
        return new Outcome(find(id), changed);
    }

    @Transactional
    public Outcome reject(long id, Decision d, long userId) {
        Suggestion s = pending(id);
        if (!repo.decide(s.id(), "REJECTED", userId, note(d))) throw alreadyDecided();
        return new Outcome(find(id), List.of());
    }

    private Suggestion pending(long id) {
        Suggestion s = find(id);
        if (!"PENDING".equals(s.status())) throw alreadyDecided();
        if (s.plannedSessionId() == null || ("SWAP".equals(s.action()) && s.counterpartSessionId() == null))
            throw new ApiException(HttpStatus.CONFLICT, "A session of this suggestion was deleted. Run the check again.", "SESSION_GONE");
        return s;
    }

    /** The sessions must still be as the suggestion saw them; otherwise it is out of date. */
    private void unchanged(Suggestion s) {
        check(s.before().path("session"), s.plannedSessionId());
        if (s.counterpartSessionId() != null) check(s.before().path("counterpart"), s.counterpartSessionId());
    }

    private void check(JsonNode before, long sessionId) {
        Session now;
        try {
            now = plans.session(sessionId);
        } catch (ApiException e) {
            throw new ApiException(HttpStatus.CONFLICT, "A session of this suggestion was deleted. Run the check again.", "SESSION_GONE");
        }
        if (!Objects.equals(before.path("updatedAt").asText(null), json.valueToTree(now).path("updatedAt").asText(null)))
            throw new ApiException(HttpStatus.CONFLICT, "The session was changed after this suggestion was made. Run the check again.",
                    "SUGGESTION_OUTDATED");
    }

    private SessionRequest request(JsonNode after, String part) {
        JsonNode n = after == null ? null : after.get(part);
        if (n == null || !n.isObject()) throw ApiException.badRequest("The suggestion has no '" + part + "' to apply");
        try {
            return json.treeToValue(n, SessionRequest.class);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("The edited session could not be read");
        }
    }

    private static String note(Decision d) {
        if (d == null || d.note() == null || d.note().isBlank()) return null;
        if (d.note().length() > MAX_NOTE) throw ApiException.badRequest("The note is longer than " + MAX_NOTE + " characters");
        return d.note().strip();
    }

    /** Same session apart from formatting: 8 and 8.0 km, an empty text and none. */
    static boolean same(SessionRequest a, SessionRequest b) {
        return Objects.equals(a.planId(), b.planId()) && Objects.equals(a.date(), b.date()) && Objects.equals(a.sessionType(), b.sessionType())
                && Objects.equals(a.demand(), b.demand()) && Objects.equals(text(a.title()), text(b.title()))
                && Objects.equals(text(a.description()), text(b.description())) && num(a.targetDistanceKm(), b.targetDistanceKm())
                && Objects.equals(a.targetDurationMin(), b.targetDurationMin()) && num(a.estStrain(), b.estStrain())
                && Objects.equals(a.status() == null ? Status.PLANNED : a.status(), b.status() == null ? Status.PLANNED : b.status())
                && Objects.equals(text(a.notes()), text(b.notes()));
    }

    private static String text(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static boolean num(java.math.BigDecimal a, java.math.BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static ApiException alreadyDecided() {
        return new ApiException(HttpStatus.CONFLICT, "This suggestion was already decided or has expired.", "SUGGESTION_DECIDED");
    }
}
