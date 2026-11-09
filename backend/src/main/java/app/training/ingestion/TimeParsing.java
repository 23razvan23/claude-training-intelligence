package app.training.ingestion;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.regex.Pattern;

/** Lenient timestamp parsing for export files whose exact formats vary. */
public final class TimeParsing {
    private TimeParsing() {}

    private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .appendPattern("HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final Pattern UTC_OFFSET = Pattern.compile("(?i)^(?:UTC|GMT)?\\s*([+-])(\\d{1,2})(?::?(\\d{2}))?$");

    /**
     * Parses a zone written as an IANA id ("Europe/Bucharest"), an offset ("+02:00"),
     * or Whoop's "UTC+02:00" style. Returns {@code fallback} when blank or unknown.
     */
    public static ZoneId zone(String text, ZoneId fallback) {
        if (text == null || text.isBlank()) return fallback;
        String t = text.trim();
        if (t.equalsIgnoreCase("UTC") || t.equalsIgnoreCase("GMT") || t.equalsIgnoreCase("Z")) return ZoneOffset.UTC;
        var m = UTC_OFFSET.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(2));
            int min = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            int sign = m.group(1).equals("-") ? -1 : 1;
            return ZoneOffset.ofHoursMinutes(sign * h, sign * min);
        }
        try {
            return ZoneId.of(t);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Parses an instant from ISO-8601 with offset, or a local date-time interpreted in {@code zone}.
     * Returns null for blank input; throws {@link DateTimeParseException} for garbage.
     */
    public static Instant instant(String text, ZoneId zone) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to local
        }
        try {
            return Instant.parse(t);
        } catch (DateTimeParseException ignored) {
            // fall through to local
        }
        return LocalDateTime.parse(t, LOCAL).atZone(zone).toInstant();
    }

    /** Epoch milliseconds as a number or numeric string (Garmin JSON often writes them as doubles). */
    public static Instant epochMillis(double ms) {
        return Instant.ofEpochMilli(Math.round(ms));
    }
}
