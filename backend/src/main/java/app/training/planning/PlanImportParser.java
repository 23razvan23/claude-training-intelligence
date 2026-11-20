package app.training.planning;

import app.training.planning.PlanModels.SessionRequest;
import app.training.planning.PlanImportModels.FileProblem;
import app.training.planning.PlanImportModels.ParsedRow;
import app.training.planning.SessionTypes.Demand;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads a plan spreadsheet (CSV or XLSX, ADR 0019 import note) into rows. Pure: no database. Tolerant where it is
 * safe (header case and spaces, a UTF-8 BOM, empty rows, ';' as the CSV separator, date cells in an XLSX), strict
 * everywhere else. A problem is reported against its spreadsheet row so the coach can find it.
 *
 * <p>Columns: {@code date,type,demand,title,workout,distance_km,duration_min,notes}; all eight must be present.
 */
public final class PlanImportParser {
    private PlanImportParser() {}

    /** Limits against mistakes (wrong file, runaway export), not training numbers [configurable]. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 500;
    private static final int MAX_UNZIPPED_BYTES = 20 * 1024 * 1024;

    public static final List<String> COLUMNS =
            List.of("date", "type", "demand", "title", "workout", "distance_km", "duration_min", "notes");

    /** One cell. {@code numeric} is only true for a number cell in an XLSX (a date there is a number). */
    record Cell(String text, boolean numeric) {
        static final Cell EMPTY = new Cell("", false);
    }

    record RawRow(int number, List<Cell> cells) {
        boolean blank() {
            return cells.stream().allMatch(c -> c.text().isBlank());
        }
    }

    /** What was read: the rows, or file-level problems (then the rows are empty). */
    public record Parsed(List<ParsedRow> rows, List<FileProblem> problems) {}

    private static final Pattern DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final Pattern DISTANCE = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final Pattern DURATION = Pattern.compile("^\\d+(\\.0+)?$");

    public static Parsed parse(byte[] data) {
        if (data == null || data.length == 0) return fail("The file is empty.");
        if (data.length > MAX_BYTES) return fail("The file is bigger than " + MAX_BYTES / (1024 * 1024) + " MB.");
        List<RawRow> raw;
        try {
            if (isZip(data)) raw = readXlsx(data);
            else if (isOldExcel(data)) return fail("This is an old Excel (.xls) file. Save it as .xlsx or CSV and try again.");
            else raw = readCsv(data);
        } catch (FormatException e) {
            return fail(e.getMessage());
        }
        return interpret(raw);
    }

    private static Parsed fail(String message) {
        return new Parsed(List.of(), List.of(new FileProblem(message)));
    }

    // ---- shared interpretation (header, then one session per row) ----

    private static Parsed interpret(List<RawRow> raw) {
        List<RawRow> rows = raw.stream().filter(r -> !r.blank()).toList();
        if (rows.isEmpty()) return fail("The file has no rows. The first row must be the header: " + String.join(",", COLUMNS));
        RawRow header = rows.get(0);
        Map<String, Integer> index = new HashMap<>();
        List<FileProblem> problems = new ArrayList<>();
        for (int i = 0; i < header.cells().size(); i++) {
            String name = normaliseHeader(header.cells().get(i).text());
            if (name.isEmpty()) continue;
            if (!COLUMNS.contains(name)) problems.add(new FileProblem("Unknown column '" + header.cells().get(i).text().strip() + "'. Columns are: " + String.join(", ", COLUMNS)));
            else if (index.putIfAbsent(name, i) != null) problems.add(new FileProblem("Column '" + name + "' appears twice"));
        }
        List<String> missing = COLUMNS.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) problems.add(new FileProblem("Missing column(s): " + String.join(", ", missing)));
        if (!problems.isEmpty()) return new Parsed(List.of(), problems);

        List<RawRow> body = rows.subList(1, rows.size());
        if (body.isEmpty()) return fail("The file has a header but no sessions.");
        if (body.size() > MAX_ROWS) return fail("The file has " + body.size() + " rows; at most " + MAX_ROWS + " at a time.");

        List<ParsedRow> out = new ArrayList<>();
        for (RawRow r : body) out.add(row(r, index, header.cells().size()));
        return new Parsed(out, List.of());
    }

    private static ParsedRow row(RawRow r, Map<String, Integer> index, int headerWidth) {
        List<String> errors = new ArrayList<>();
        for (int i = headerWidth; i < r.cells().size(); i++)
            if (!r.cells().get(i).text().isBlank()) {
                errors.add("has more values than there are columns (a comma inside text needs quotes)");
                break;
            }
        Cell date = cell(r, index, "date");
        LocalDate d = parseDate(date, errors);
        String typeText = cell(r, index, "type").text().strip();
        String type = typeText.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        Demand demand = parseDemand(cell(r, index, "demand").text().strip(), errors);
        BigDecimal km = parseDistance(cell(r, index, "distance_km"), errors);
        Integer min = parseDuration(cell(r, index, "duration_min"), errors);
        for (String name : COLUMNS)
            if (hasControl(cell(r, index, name).text())) {
                errors.add(name + " contains characters that can't be stored");
                break;
            }
        // The shared rules (known type, limits) are SessionValidator's, so typed and imported sessions agree.
        var request = new SessionRequest(null, d, type.isEmpty() ? null : type, demand,
                cell(r, index, "title").text(), cell(r, index, "workout").text(), km, min, null, null,
                cell(r, index, "notes").text(), null);
        for (String p : SessionValidator.check(request).problems()) {
            if (p.equals("Date is missing")) continue; // already reported above, in the coach's own words
            if (p.equals("Unknown type '" + type + "'")) p = "Unknown type '" + typeText + "'"; // the coach's spelling
            if (!errors.contains(p)) errors.add(p);
        }
        return new ParsedRow(r.number(), request, errors.stream().map(PlanImportParser::lowerFirst).toList());
    }

    /** "Unknown type 'x'" reads as "row 7: unknown type 'x'"; leaves "VO2max"-style starts alone. */
    private static String lowerFirst(String s) {
        return s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isLowerCase(s.charAt(1))
                ? Character.toLowerCase(s.charAt(0)) + s.substring(1) : s;
    }

    private static Cell cell(RawRow r, Map<String, Integer> index, String column) {
        int i = index.get(column);
        return i < r.cells().size() ? r.cells().get(i) : Cell.EMPTY;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return true;
        }
        return false;
    }

    static String normaliseHeader(String h) {
        return h.replace("﻿", "").strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    private static LocalDate parseDate(Cell c, List<String> errors) {
        String t = c.text().strip();
        if (t.isEmpty()) {
            errors.add("date is missing");
            return null;
        }
        if (c.numeric()) { // An Excel date is a day count from 1899-12-30 (the 1900 system).
            try {
                double serial = Double.parseDouble(t);
                if (serial >= 1 && serial < 100000) return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(serial));
            } catch (NumberFormatException ignored) {
                // falls through to the message below
            }
            errors.add("date '" + t + "' is not a date. Use DD/MM/YYYY, for example 25/11/2026");
            return null;
        }
        Matcher m = DATE.matcher(t);
        if (m.matches()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            } catch (java.time.DateTimeException e) {
                errors.add("date '" + t + "' does not exist");
                return null;
            }
        }
        errors.add("date '" + t + "' must be DD/MM/YYYY, for example 25/11/2026");
        return null;
    }

    private static Demand parseDemand(String t, List<String> errors) {
        if (t.isEmpty()) return null; // the type's default
        try {
            return Demand.valueOf(t.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("unknown demand '" + t + "' (use key, supporting or easy, or leave it empty)");
            return null;
        }
    }

    private static BigDecimal parseDistance(Cell c, List<String> errors) {
        String t = c.text().strip();
        if (t.isEmpty()) return null;
        if (!DISTANCE.matcher(t).matches()) {
            errors.add("distance_km '" + t + "' must be a number with a dot, like 12.5");
            return null;
        }
        BigDecimal v = new BigDecimal(t);
        return c.numeric() && v.scale() > 3 ? v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros() : v;
    }

    private static Integer parseDuration(Cell c, List<String> errors) {
        String t = c.text().strip();
        if (t.isEmpty()) return null;
        if (!DURATION.matcher(t).matches() || t.length() > 7) {
            errors.add("duration_min '" + t + "' must be a whole number of minutes, like 60");
            return null;
        }
        return new BigDecimal(t).intValue();
    }

    // ---- CSV ----

    private static List<RawRow> readCsv(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.indexOf('�') >= 0) throw new FormatException("The file is not UTF-8 text. In Excel use 'CSV UTF-8' when saving.");
        if (text.startsWith("﻿")) text = text.substring(1);
        char delimiter = delimiter(text);
        List<RawRow> rows = new ArrayList<>();
        try (CSVParser p = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false)
                .setTrim(false).build().parse(new StringReader(text))) {
            for (CSVRecord rec : p) {
                List<Cell> cells = new ArrayList<>();
                for (String v : rec) cells.add(new Cell(v == null ? "" : v, false));
                rows.add(new RawRow((int) rec.getRecordNumber(), cells));
            }
        } catch (IOException | RuntimeException e) {
            throw new FormatException("The CSV could not be read (" + rootMessage(e) + "). Check the quotes around text.");
        }
        return rows;
    }

    /** ';' when the header line has more of them than ','; some Excel regions save CSV that way. */
    private static char delimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        return first.chars().filter(c -> c == ';').count() > first.chars().filter(c -> c == ',').count() ? ';' : ',';
    }

    // ---- XLSX (first sheet, header row; no formulas, merged cells or other sheets) ----

    private static boolean isZip(byte[] d) {
        return d.length > 3 && d[0] == 'P' && d[1] == 'K';
    }

    private static boolean isOldExcel(byte[] d) {
        return d.length > 4 && (d[0] & 0xFF) == 0xD0 && (d[1] & 0xFF) == 0xCF && (d[2] & 0xFF) == 0x11 && (d[3] & 0xFF) == 0xE0;
    }

    private static List<RawRow> readXlsx(byte[] data) {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String name = e.getName();
                if (!(name.equals("xl/workbook.xml") || name.equals("xl/_rels/workbook.xml.rels")
                        || name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/"))) continue;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n; (n = z.read(chunk)) > 0; ) {
                    total += n;
                    if (total > MAX_UNZIPPED_BYTES) throw new FormatException("The spreadsheet is too large.");
                    buf.write(chunk, 0, n);
                }
                parts.put(name, buf.toByteArray());
            }
        } catch (IOException e) {
            throw new FormatException("The file is not a readable .xlsx spreadsheet.");
        }
        if (!parts.containsKey("xl/workbook.xml")) throw new FormatException("The file is not a readable .xlsx spreadsheet.");
        try {
            String sheetPath = firstSheetPath(parts);
            byte[] sheet = parts.get(sheetPath);
            if (sheet == null) throw new FormatException("The first sheet could not be found in the spreadsheet.");
            List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            return sheetRows(sheet, shared);
        } catch (XMLStreamException e) {
            throw new FormatException("The spreadsheet could not be read (" + rootMessage(e) + ").");
        }
    }

    private static XMLStreamReader xml(byte[] bytes) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    private static String firstSheetPath(Map<String, byte[]> parts) throws XMLStreamException {
        String rid = null;
        XMLStreamReader r = xml(parts.get("xl/workbook.xml"));
        while (r.hasNext() && rid == null)
            if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                for (int i = 0; i < r.getAttributeCount(); i++)
                    if (r.getAttributeLocalName(i).equals("id")) rid = r.getAttributeValue(i);
            }
        if (rid == null) throw new FormatException("The spreadsheet has no sheets.");
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (rels == null) return "xl/worksheets/sheet1.xml";
        XMLStreamReader rr = xml(rels);
        while (rr.hasNext())
            if (rr.next() == XMLStreamConstants.START_ELEMENT && rr.getLocalName().equals("Relationship")
                    && rid.equals(rr.getAttributeValue(null, "Id"))) {
                String target = rr.getAttributeValue(null, "Target");
                return target.startsWith("/") ? target.substring(1) : "xl/" + target;
            }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] bytes) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = xml(bytes);
        StringBuilder cur = null;
        boolean inText = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (r.getLocalName().equals("si")) cur = new StringBuilder();
                else if (r.getLocalName().equals("t") && cur != null) inText = true;
            } else if (ev == XMLStreamConstants.CHARACTERS && inText) {
                cur.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("t")) inText = false;
                else if (r.getLocalName().equals("si")) {
                    out.add(cur.toString());
                    cur = null;
                }
            }
        }
        return out;
    }

    private static List<RawRow> sheetRows(byte[] bytes, List<String> shared) throws XMLStreamException {
        List<RawRow> rows = new ArrayList<>();
        XMLStreamReader r = xml(bytes);
        int rowNumber = 0;
        Map<Integer, Cell> cells = null;
        String type = null;
        int col = -1;
        StringBuilder text = null;
        boolean inValue = false, inInline = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        String n = r.getAttributeValue(null, "r");
                        rowNumber = n != null ? Integer.parseInt(n) : rowNumber + 1;
                        cells = new HashMap<>();
                    }
                    case "c" -> {
                        type = r.getAttributeValue(null, "t");
                        String ref = r.getAttributeValue(null, "r");
                        col = ref != null ? columnIndex(ref) : col + 1;
                        text = new StringBuilder();
                    }
                    case "v" -> inValue = true;
                    case "is" -> inInline = true;
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.CHARACTERS) {
                if ((inValue || inInline) && text != null) text.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v" -> inValue = false;
                    case "is" -> inInline = false;
                    case "c" -> {
                        if (cells != null && text != null) cells.put(col, toCell(type, text.toString(), shared));
                        text = null;
                    }
                    case "row" -> {
                        int width = cells.keySet().stream().mapToInt(i -> i + 1).max().orElse(0);
                        List<Cell> list = new ArrayList<>();
                        for (int i = 0; i < width; i++) list.add(cells.getOrDefault(i, Cell.EMPTY));
                        rows.add(new RawRow(rowNumber, list));
                        cells = null;
                    }
                    default -> { }
                }
            }
        }
        return rows;
    }

    private static Cell toCell(String type, String raw, List<String> shared) {
        if (type == null || type.equals("n")) return new Cell(raw.strip(), !raw.isBlank());
        return switch (type) {
            case "s" -> {
                try {
                    yield new Cell(shared.get(Integer.parseInt(raw.strip())), false);
                } catch (RuntimeException e) {
                    yield Cell.EMPTY;
                }
            }
            case "b" -> new Cell(raw.strip().equals("1") ? "TRUE" : "FALSE", false);
            case "e" -> new Cell("#ERROR", false);
            default -> new Cell(raw, false); // str, inlineStr
        };
    }

    /** "C12" gives 2 (A = 0). */
    private static int columnIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++)
            n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        return n - 1;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.lines().findFirst().orElse("unreadable");
    }

    /** A problem with the file as a whole. */
    private static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message);
        }
    }
}
