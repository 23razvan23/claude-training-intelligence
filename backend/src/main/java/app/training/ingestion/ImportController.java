package app.training.ingestion;

import app.training.common.ApiException;
import app.training.security.AccessPolicy;
import app.training.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Uploads return 202 with a PENDING batch; poll GET /api/imports/{id} for the result. */
@RestController
@RequestMapping("/api")
public class ImportController {
    private final ImportService imports;
    private final ImportBatchRepository batches;
    private final AccessPolicy access;

    public ImportController(ImportService imports, ImportBatchRepository batches, AccessPolicy access) {
        this.imports = imports;
        this.batches = batches;
        this.access = access;
    }

    /** Whoop export: the export ZIP, or its CSVs (physiological_cycles, sleeps, workouts, journal_entries). */
    @PostMapping(path = "/athletes/{athleteId}/imports/whoop", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('COACH')")
    public ImportBatch whoop(@PathVariable long athleteId, @RequestPart("files") List<MultipartFile> files,
                             @AuthenticationPrincipal CurrentUser user) {
        return imports.start(athleteId, ImportKind.WHOOP_EXPORT, files, user.id());
    }

    /** Garmin Connect account export ZIP. */
    @PostMapping(path = "/athletes/{athleteId}/imports/garmin", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('COACH')")
    public ImportBatch garmin(@PathVariable long athleteId, @RequestPart("file") MultipartFile file,
                              @AuthenticationPrincipal CurrentUser user) {
        return imports.start(athleteId, ImportKind.GARMIN_EXPORT, List.of(file), user.id());
    }

    /** One or more FIT activity files: the fallback when sync breaks. */
    @PostMapping(path = "/athletes/{athleteId}/imports/fit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('COACH')")
    public ImportBatch fit(@PathVariable long athleteId, @RequestPart("files") List<MultipartFile> files,
                           @AuthenticationPrincipal CurrentUser user) {
        return imports.start(athleteId, ImportKind.FIT_UPLOAD, files, user.id());
    }

    @GetMapping("/athletes/{athleteId}/imports")
    public List<ImportBatch> list(@PathVariable long athleteId, @AuthenticationPrincipal CurrentUser user) {
        access.requireAthlete(user, athleteId);
        return batches.listForAthlete(athleteId, 50);
    }

    @GetMapping("/imports/{id}")
    public ImportBatch get(@PathVariable long id, @AuthenticationPrincipal CurrentUser user) {
        var batch = batches.find(id).orElseThrow(() -> ApiException.notFound("Import"));
        access.requireAthlete(user, batch.athleteId());
        return batch;
    }
}
