package app.training.ingestion;

import app.training.athletes.AthleteRepository;
import app.training.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportService {
    private final ImportBatchRepository batches;
    private final AthleteRepository athletes;
    private final ImportRunner runner;
    private final Path workDir;

    public ImportService(ImportBatchRepository batches, AthleteRepository athletes, ImportRunner runner,
                         @Value("${app.import.work-dir}") String workDir) {
        this.batches = batches;
        this.athletes = athletes;
        this.runner = runner;
        this.workDir = Path.of(workDir);
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() throws IOException {
        Files.createDirectories(workDir);
        batches.failInterrupted();
    }

    /** Stores the uploads, records a PENDING batch and starts it in the background. */
    public ImportBatch start(long athleteId, ImportKind kind, List<MultipartFile> files, long userId) {
        athletes.findById(athleteId).orElseThrow(() -> ApiException.notFound("Athlete"));
        if (files == null || files.isEmpty() || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw ApiException.badRequest("No file uploaded");
        }
        if (kind == ImportKind.GARMIN_EXPORT && files.size() != 1) {
            throw ApiException.badRequest("Upload the Garmin export as a single ZIP file");
        }
        var stored = new ArrayList<Path>();
        String sha256;
        try {
            Files.createDirectories(workDir);
            var digest = MessageDigest.getInstance("SHA-256");
            for (MultipartFile f : files) {
                if (f.isEmpty()) continue;
                Path target = workDir.resolve(UUID.randomUUID() + suffix(f.getOriginalFilename()));
                try (InputStream in = new DigestInputStream(f.getInputStream(), digest)) {
                    Files.copy(in, target);
                }
                stored.add(target);
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            stored.forEach(p -> p.toFile().delete());
            throw new IllegalStateException("Could not store upload", e);
        }
        String name = files.size() == 1 ? files.getFirst().getOriginalFilename() : files.size() + " files";
        long batchId = batches.create(athleteId, kind, name, sha256, userId);
        runner.runAsync(batchId, athleteId, kind, stored, sha256);
        return batches.find(batchId).orElseThrow();
    }

    private static String suffix(String original) {
        if (original == null) return "";
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : "";
    }
}
