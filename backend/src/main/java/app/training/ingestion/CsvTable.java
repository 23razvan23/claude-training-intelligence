package app.training.ingestion;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * A CSV file read with tolerant header matching: headers are compared after
 * lower-casing and stripping everything that is not a letter or digit, so
 * "Recovery score %" matches "recovery_score". Each logical column may list
 * several aliases.
 */
public final class CsvTable {
    private final List<String> headers;
    private final Map<String, Integer> index = new HashMap<>();
    private final List<CSVRecord> records;

    private CsvTable(List<String> headers, List<CSVRecord> records) {
        this.headers = headers;
        this.records = records;
        for (int i = 0; i < headers.size(); i++) index.putIfAbsent(normalize(headers.get(i)), i);
    }

    public static CsvTable read(Reader reader) throws IOException {
        var format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setAllowMissingColumnNames(true)
                .setDuplicateHeaderMode(org.apache.commons.csv.DuplicateHeaderMode.ALLOW_ALL)
                .build();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            var headers = new ArrayList<String>();
            for (String h : parser.getHeaderNames()) headers.add(stripBom(h));
            return new CsvTable(headers, parser.getRecords());
        }
    }

    public static String normalize(String header) {
        return stripBom(header).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    public List<String> headers() {
        return headers;
    }

    public List<CSVRecord> records() {
        return records;
    }

    public boolean has(Column c) {
        return indexOf(c) >= 0;
    }

    private int indexOf(Column c) {
        for (String alias : c.aliases()) {
            Integer i = index.get(normalize(alias));
            if (i != null) return i;
        }
        return -1;
    }

    public String text(CSVRecord r, Column c) {
        int i = indexOf(c);
        if (i < 0 || i >= r.size()) return null;
        String v = r.get(i);
        return v == null || v.isBlank() ? null : v.trim();
    }

    public BigDecimal decimal(CSVRecord r, Column c) {
        String v = text(r, c);
        if (v == null) return null;
        try {
            return new BigDecimal(v.replace(",", "").replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Integer integer(CSVRecord r, Column c) {
        BigDecimal d = decimal(r, c);
        return d == null ? null : d.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
    }

    public Boolean bool(CSVRecord r, Column c) {
        String v = text(r, c);
        if (v == null) return null;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> true;
            case "false", "no", "n", "0" -> false;
            default -> null;
        };
    }

    /** A logical column and the header spellings it may appear under. */
    public interface Column {
        List<String> aliases();
    }
}
