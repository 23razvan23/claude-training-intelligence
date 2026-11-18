package app.training.planning;

import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.SessionTypes.Demand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The shapes the spreadsheet import reads and returns (ADR 0019 import note). */
public final class PlanImportModels {
    private PlanImportModels() {}

    /** A problem with the file as a whole (wrong columns, unreadable). */
    public record FileProblem(String message) {}

    /** One spreadsheet row as understood: the session (null fields where it was wrong) and what is wrong with it. */
    public record ParsedRow(int row, SessionRequest request, List<String> errors) {}

    /** One line of the preview. Texts are already in plain words ("unknown type 'tempo'"). */
    public record PreviewRow(int row, LocalDate date, String sessionType, Demand demand, String title, String workout,
                             BigDecimal distanceKm, Integer durationMin, String notes, List<String> errors,
                             List<String> warnings) {}

    /** What pressing Import would do. {@code canImport} is false while there is any error. Nothing is saved. */
    public record Preview(boolean canImport, List<String> fileErrors, int rowCount, int errorCount, int warningCount,
                          Long planId, String planName, LocalDate planStart, LocalDate planEnd,
                          LocalDate newPlanStart, LocalDate newPlanEnd, List<PreviewRow> rows) {}

    /** The result of an import. */
    public record Imported(int imported, long planId, boolean stretchedPlan) {}
}
