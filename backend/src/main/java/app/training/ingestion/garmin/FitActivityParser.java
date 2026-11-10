package app.training.ingestion.garmin;

import app.training.ingestion.RawRows.StreamPoint;
import com.garmin.fit.Decode;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.FileIdMesgListener;
import com.garmin.fit.FitRuntimeException;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.RecordMesgListener;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SessionMesgListener;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Decodes a FIT activity file into a session summary plus its per-second stream. */
public final class FitActivityParser {
    private FitActivityParser() {}

    public record Session(
            Instant start, String sport, String subSport, Float timerTimeS, Float elapsedTimeS, Float distanceM,
            Integer ascentM, Integer avgHr, Integer maxHr, Integer calories, Float aerobicTe, Float anaerobicTe) {}

    /**
     * @param activity   false when the file is not an activity (monitoring, settings, courses...)
     * @param sessions   one per sport in the file; multisport files have several
     */
    public record Parsed(boolean activity, Long serialNumber, Instant timeCreated, List<Session> sessions,
                         List<StreamPoint> stream, String fileType) {
        /** All-day monitoring files carry the background heart rate (see FitMonitoringParser). */
        public boolean monitoring() {
            return fileType != null && fileType.startsWith("MONITORING");
        }
    }

    public static Parsed parse(byte[] bytes) {
        var decode = new Decode();
        var broadcaster = new MesgBroadcaster(decode);
        var sessions = new ArrayList<Session>();
        var stream = new ArrayList<StreamPoint>();
        var fileId = new FileIdMesg[1];

        broadcaster.addListener((FileIdMesgListener) m -> {
            if (fileId[0] != null) return;
            fileId[0] = m;
            // file_id is the first message: stop right away for non-activity files
            // (bulk exports hold ~15x more monitoring files than activities).
            if (m.getType() != com.garmin.fit.File.ACTIVITY) throw new NotAnActivity();
        });
        broadcaster.addListener((SessionMesgListener) m -> sessions.add(session(m)));
        broadcaster.addListener((RecordMesgListener) m -> {
            if (m.getTimestamp() == null) return;
            stream.add(new StreamPoint(
                    m.getTimestamp().getDate().toInstant(),
                    toInt(m.getHeartRate()),
                    m.getEnhancedSpeed() != null ? m.getEnhancedSpeed() : m.getSpeed(),
                    toInt(m.getCadence()),
                    m.getPower(),
                    m.getEnhancedAltitude() != null ? m.getEnhancedAltitude() : m.getAltitude(),
                    m.getDistance()));
        });

        try {
            decode.read(new ByteArrayInputStream(bytes), broadcaster, broadcaster);
        } catch (NotAnActivity e) {
            FileIdMesg id = fileId[0];
            return new Parsed(false, id.getSerialNumber(), null, List.of(), List.of(), typeName(id));
        } catch (FitRuntimeException e) {
            if (e.getCause() instanceof NotAnActivity || hasCause(e, NotAnActivity.class)) {
                return new Parsed(false, fileId[0] == null ? null : fileId[0].getSerialNumber(), null, List.of(), List.of(),
                        fileId[0] == null ? null : typeName(fileId[0]));
            }
            throw new IllegalArgumentException("Not a readable FIT file: " + e.getMessage(), e);
        }

        FileIdMesg id = fileId[0];
        boolean isActivity = id != null && id.getType() == com.garmin.fit.File.ACTIVITY && !sessions.isEmpty();
        return new Parsed(
                isActivity,
                id == null ? null : id.getSerialNumber(),
                id == null || id.getTimeCreated() == null ? null : id.getTimeCreated().getDate().toInstant(),
                sessions,
                stream,
                id == null ? null : typeName(id));
    }

    private static String typeName(FileIdMesg id) {
        return id.getType() == null ? null : id.getType().name();
    }

    /** Thrown from the file_id listener to abort decoding a non-activity file. */
    private static final class NotAnActivity extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotAnActivity() {
            super(null, null, false, false);
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            if (type.isInstance(c)) return true;
        }
        return false;
    }

    private static Session session(SessionMesg m) {
        return new Session(
                m.getStartTime() == null ? null : m.getStartTime().getDate().toInstant(),
                m.getSport() == null ? null : m.getSport().name(),
                m.getSubSport() == null ? null : m.getSubSport().name(),
                m.getTotalTimerTime(),
                m.getTotalElapsedTime(),
                m.getTotalDistance(),
                m.getTotalAscent(),
                toInt(m.getAvgHeartRate()),
                toInt(m.getMaxHeartRate()),
                m.getTotalCalories(),
                m.getTotalTrainingEffect(),
                m.getTotalAnaerobicTrainingEffect());
    }

    private static Integer toInt(Short s) {
        return s == null ? null : s.intValue();
    }
}
