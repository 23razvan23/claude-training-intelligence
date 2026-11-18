package app.training.planning;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import app.training.planning.PlanImportModels.FileProblem;
import app.training.planning.PlanImportModels.Imported;
import app.training.planning.PlanImportModels.ParsedRow;
import app.training.planning.PlanImportModels.Preview;
import app.training.planning.PlanImportModels.PreviewRow;
import app.training.planning.PlanModels.Plan;
import app.training.planning.PlanModels.Session;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spreadsheet import of a plan (stage 2-1a PR 2, ADR 0019 import note). Preview reads the file and saves nothing;
 * commit reads it again and saves every row in one transaction, through {@link PlanService#createSession}, so an
 * imported session follows exactly the rules of a typed one (one ACTIVE plan, the plan stretches, one history row
 * per session). Nothing is kept between the two calls: the file is sent twice, so a preview can never go stale.
 */
@Service
public class PlanImportService {
    private final PlanService plans;
    private final PlanRepository repo;
    private final AthleteRepository athletes;

    public PlanImportService(PlanService plans, PlanRepository repo, AthleteRepository athletes) {
        this.plans = plans;
        this.repo = repo;
        this.athletes = athletes;
    }

    public Preview preview(long athleteId, byte[] file) {
        var athlete = athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        var parsed = PlanImportParser.parse(file);
        List<String> fileErrors = new ArrayList<>(parsed.problems().stream().map(FileProblem::message).toList());
        Plan plan = repo.activePlan(athleteId).orElse(null);
        if (plan == null) fileErrors.add("This athlete has no active plan. Create a plan first, then import.");

        LocalDate today = LocalDate.now(zone(athlete.timezone()));
        List<ParsedRow> rows = parsed.rows();
        Map<LocalDate, List<Session>> existing = new HashMap<>();
        Map<LocalDate, Integer> inFile = new HashMap<>();
        LocalDate min = null, max = null;
        for (ParsedRow r : rows) {
            LocalDate d = r.request().date();
            if (d == null) continue;
            inFile.merge(d, 1, Integer::sum);
            min = min == null || d.isBefore(min) ? d : min;
            max = max == null || d.isAfter(max) ? d : max;
        }
        if (min != null)
            for (Session s : repo.sessionsInRange(athleteId, min, max)) existing.computeIfAbsent(s.date(), k -> new ArrayList<>()).add(s);

        List<PreviewRow> out = new ArrayList<>();
        int errors = 0, warnings = 0;
        for (ParsedRow r : rows) {
            var q = r.request();
            List<String> warns = new ArrayList<>();
            if (q.date() != null) {
                var already = existing.getOrDefault(q.date(), List.of());
                if (!already.isEmpty())
                    warns.add(already.size() == 1 ? "this date already has a session (" + already.get(0).sessionType() + "); the new one is added next to it"
                            : "this date already has " + already.size() + " sessions; the new one is added next to them");
                if (inFile.get(q.date()) > 1) warns.add("another row in this file has the same date");
                if (q.date().isBefore(today)) warns.add("this date is in the past");
                if (plan != null && (q.date().isBefore(plan.startDate()) || q.date().isAfter(plan.endDate())))
                    warns.add("this date is outside the plan's dates; the plan will be stretched to include it");
            }
            errors += r.errors().isEmpty() ? 0 : 1;
            warnings += warns.isEmpty() ? 0 : 1;
            out.add(new PreviewRow(r.row(), q.date(), q.sessionType(), q.demand(), q.title(), q.description(),
                    q.targetDistanceKm(), q.targetDurationMin(), q.notes(), r.errors().stream().map(e -> "row " + r.row() + ": " + e).toList(), warns));
        }
        boolean can = fileErrors.isEmpty() && errors == 0 && !rows.isEmpty();
        LocalDate newStart = null, newEnd = null;
        if (plan != null && min != null) {
            newStart = min.isBefore(plan.startDate()) ? min : plan.startDate();
            newEnd = max.isAfter(plan.endDate()) ? max : plan.endDate();
        }
        return new Preview(can, fileErrors, rows.size(), errors, warnings, plan == null ? null : plan.id(),
                plan == null ? null : plan.name(), plan == null ? null : plan.startDate(),
                plan == null ? null : plan.endDate(), newStart, newEnd, out);
    }

    /** All rows or none: a file with any error is refused, and any failure while saving rolls everything back. */
    @Transactional
    public Imported commit(long athleteId, byte[] file, long userId) {
        Preview p = preview(athleteId, file);
        if (!p.canImport()) {
            List<String> why = new ArrayList<>(p.fileErrors());
            p.rows().forEach(r -> why.addAll(r.errors()));
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Nothing was imported. " + String.join("; ", why.stream().limit(5).toList())
                            + (why.size() > 5 ? "; and " + (why.size() - 5) + " more" : ""), "IMPORT_HAS_ERRORS");
        }
        var rows = PlanImportParser.parse(file).rows();
        for (ParsedRow r : rows) plans.createSession(athleteId, r.request(), userId);
        boolean stretched = !p.newPlanStart().equals(p.planStart()) || !p.newPlanEnd().equals(p.planEnd());
        return new Imported(rows.size(), p.planId(), stretched);
    }

    private static ZoneId zone(String tz) {
        try {
            return tz == null ? ZoneId.of("UTC") : ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }
}
