package app.training.ingestion.garmin;

import com.garmin.fit.DateTime;
import com.garmin.fit.Decode;
import com.garmin.fit.FitRuntimeException;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.MonitoringInfoMesgListener;
import com.garmin.fit.MonitoringMesgListener;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts all-day heart-rate samples from a Garmin monitoring FIT file.
 *
 * <p>Monitoring messages usually carry only {@code timestamp_16}: the low 16 bits of the
 * FIT timestamp, relative to the last full timestamp seen in the file. The full value is
 * rebuilt as {@code last + ((ts16 − (last & 0xFFFF)) & 0xFFFF)}, which handles the 18-hour
 * rollover.
 */
public final class FitMonitoringParser {
    private FitMonitoringParser() {}

    /** Plausible all-day HR; 0 and 255 mean "no reading". */
    static final int MIN_HR = 25;
    static final int MAX_HR = 230;

    public record Sample(Instant ts, int hr) {}

    public static List<Sample> parse(byte[] bytes) {
        var decode = new Decode();
        var broadcaster = new MesgBroadcaster(decode);
        var out = new ArrayList<Sample>();
        long[] last = {-1}; // last full FIT timestamp (seconds since 1989-12-31)

        broadcaster.addListener((MonitoringInfoMesgListener) m -> {
            if (m.getTimestamp() != null) last[0] = m.getTimestamp().getTimestamp();
        });
        broadcaster.addListener((MonitoringMesgListener) m -> {
            long ts;
            if (m.getTimestamp() != null) {
                ts = m.getTimestamp().getTimestamp();
                last[0] = ts;
            } else if (m.getTimestamp16() != null && last[0] >= 0) {
                ts = expand(last[0], m.getTimestamp16());
                last[0] = ts;
            } else {
                return;
            }
            Short hr = m.getHeartRate();
            if (hr != null && hr >= MIN_HR && hr <= MAX_HR) {
                out.add(new Sample(new DateTime(ts).getDate().toInstant(), hr));
            }
        });
        try {
            decode.read(new ByteArrayInputStream(bytes), broadcaster, broadcaster);
        } catch (FitRuntimeException e) {
            throw new IllegalArgumentException("Not a readable FIT file: " + e.getMessage(), e);
        }
        return out;
    }

    /** Rebuilds a full FIT timestamp from the previous full one and a 16-bit offset. */
    static long expand(long lastFull, int ts16) {
        return lastFull + ((ts16 - (lastFull & 0xFFFF)) & 0xFFFF);
    }
}
