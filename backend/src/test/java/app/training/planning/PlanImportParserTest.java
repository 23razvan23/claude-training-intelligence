package app.training.planning;

import static org.assertj.core.api.Assertions.assertThat;

import app.training.planning.PlanImportModels.ParsedRow;
import app.training.planning.SessionTypes.Demand;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading CSV and XLSX plans and the plain row messages. Synthetic rows only. */
class PlanImportParserTest {
    private static final String HEADER = "date,type,demand,title,workout,distance_km,duration_min,notes\n";

    private static byte[] csv(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ParsedRow only(byte[] file) {
        var p = PlanImportParser.parse(file);
        assertThat(p.problems()).isEmpty();
        assertThat(p.rows()).hasSize(1);
        return p.rows().get(0);
    }

    @Test
    void readsACsvRowIncludingQuotedTextAndDefaults() {
        var p = PlanImportParser.parse(csv(HEADER
                + "25/11/2026,threshold,,Tempo,\"3 km WU, 20 min @ T\",12.5,60,\"Felt good,\nsee notes\"\n"
                + "26/11/2026,rest,,,,,,\n"));
        assertThat(p.problems()).isEmpty();
        var a = p.rows().get(0);
        assertThat(a.row()).isEqualTo(2);
        assertThat(a.errors()).isEmpty();
        var s = a.request();
        assertThat(s.date()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(s.sessionType()).isEqualTo("threshold");
        assertThat(s.demand()).isNull(); // empty: the type's default, filled in by the validator when saved
        assertThat(s.description()).isEqualTo("3 km WU, 20 min @ T");
        assertThat(s.targetDistanceKm()).isEqualByComparingTo("12.5");
        assertThat(s.targetDurationMin()).isEqualTo(60);
        assertThat(s.notes()).contains("see notes");
        var rest = p.rows().get(1);
        assertThat(rest.errors()).isEmpty();
        assertThat(rest.request().sessionType()).isEqualTo("rest");
    }

    @Test
    void toleratesBomHeaderCaseSpacesBlankRowsAndSemicolons() {
        String text = "﻿ Date ; TYPE ;Demand;Title;Workout;Distance KM;Duration Min;Notes\n"
                + "\n25/11/2026;Long Fast Finish;KEY;;;30,0;;\n;;;;;;;\n";
        var p = PlanImportParser.parse(csv(text));
        // ';' is the separator here, so "30,0" is a text cell and is refused (the dot rule), but the rest reads fine.
        assertThat(p.problems()).isEmpty();
        assertThat(p.rows()).hasSize(1);
        assertThat(p.rows().get(0).request().sessionType()).isEqualTo("long_fast_finish");
        assertThat(p.rows().get(0).request().demand()).isEqualTo(Demand.KEY);
        assertThat(p.rows().get(0).errors()).containsExactly("distance_km '30,0' must be a number with a dot, like 12.5");
    }

    @Test
    void sayWhichRowAndWhatIsWrong() {
        var p = PlanImportParser.parse(csv(HEADER
                + "25/11/2026,easy,,,,8,45,\n"
                + "26/11/2026,tempo,,,,,,\n"
                + "2026-11-27,easy,,,,,,\n"
                + "31/02/2026,easy,,,,,,\n"
                + "28/11/2026,easy,important,,,,,\n"
                + "29/11/2026,easy,,,,12.5km,,\n"
                + "30/11/2026,easy,,,,,45.5,\n"
                + "01/12/2026,easy,,,,600,,\n"
                + "02/12/2026,easy,,,run,with,5,10,note,extra\n"));
        var rows = p.rows();
        assertThat(rows.get(0).errors()).isEmpty();
        assertThat(rows.get(1).errors()).containsExactly("unknown type 'tempo'");
        assertThat(rows.get(2).errors()).containsExactly("date '2026-11-27' must be DD/MM/YYYY, for example 25/11/2026");
        assertThat(rows.get(3).errors()).containsExactly("date '31/02/2026' does not exist");
        assertThat(rows.get(4).errors()).containsExactly("unknown demand 'important' (use key, supporting or easy, or leave it empty)");
        assertThat(rows.get(5).errors()).containsExactly("distance_km '12.5km' must be a number with a dot, like 12.5");
        assertThat(rows.get(6).errors()).containsExactly("duration_min '45.5' must be a whole number of minutes, like 60");
        assertThat(rows.get(7).errors()).containsExactly("distance must be between 0 and 500 km"); // SessionValidator's own rule
        assertThat(rows.get(8).errors()).anyMatch(e -> e.contains("more values than there are columns"));
        assertThat(rows.get(1).row()).isEqualTo(3);
    }

    @Test
    void refusesAFileWithTheWrongColumns() {
        var unknown = PlanImportParser.parse(csv("date,type,demand,title,workout,distance_km,duration_min,notes,colour\n1/1/2027,easy,,,,,,,red\n"));
        assertThat(unknown.problems()).anyMatch(x -> x.message().startsWith("Unknown column 'colour'"));
        var missing = PlanImportParser.parse(csv("date,type\n01/01/2027,easy\n"));
        assertThat(missing.problems()).singleElement().satisfies(x -> assertThat(x.message())
                .isEqualTo("Missing column(s): demand, title, workout, distance_km, duration_min, notes"));
        var twice = PlanImportParser.parse(csv("date,date,type,demand,title,workout,distance_km,duration_min,notes\n"));
        assertThat(twice.problems()).anyMatch(x -> x.message().equals("Column 'date' appears twice"));
        assertThat(PlanImportParser.parse(csv("")).problems()).isNotEmpty();
        assertThat(PlanImportParser.parse(csv(HEADER)).problems()).singleElement()
                .satisfies(x -> assertThat(x.message()).isEqualTo("The file has a header but no sessions."));
        assertThat(PlanImportParser.parse(csv("\n\n")).problems()).isNotEmpty();
    }

    @Test
    void refusesFilesThatAreTooBigTooLongOrNotText() {
        assertThat(PlanImportParser.parse(new byte[PlanImportParser.MAX_BYTES + 1]).problems()).singleElement()
                .satisfies(x -> assertThat(x.message()).contains("bigger than 2 MB"));
        StringBuilder many = new StringBuilder(HEADER);
        for (int i = 0; i <= PlanImportParser.MAX_ROWS; i++) many.append("01/01/2027,easy,,,,,,\n");
        assertThat(PlanImportParser.parse(csv(many.toString())).problems()).singleElement()
                .satisfies(x -> assertThat(x.message()).contains("at most 500"));
        assertThat(PlanImportParser.parse(new byte[] {(byte) 0xff, (byte) 0xfe, 0x41, (byte) 0xe9}).problems()).isNotEmpty();
        byte[] xls = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0};
        assertThat(PlanImportParser.parse(xls).problems().get(0).message()).contains("old Excel");
        assertThat(PlanImportParser.parse(new byte[] {'P', 'K', 3, 4, 0, 0}).problems().get(0).message()).contains("not a readable .xlsx");
    }

    @Test
    void readsTheFirstSheetOfAnXlsxWithRealDatesAndNumbers() {
        List<List<Object>> sheet = new ArrayList<>();
        sheet.add(Arrays.asList(PlanImportParser.COLUMNS.toArray()));
        // 46351 is 25/11/2026 as an Excel date cell; 12.5 and 60 are number cells; the third row has a text date.
        sheet.add(Arrays.asList(46351, "threshold", null, "Tempo", "3 km WU & 20 min @ T", 12.5, 60, "<ok>"));
        sheet.add(Arrays.asList("26/11/2026", "Easy", "supporting", null, null, 8.0000000000001, 45.0));
        sheet.add(Arrays.asList(null, null, null));
        var p = PlanImportParser.parse(XlsxFixture.build(sheet));
        assertThat(p.problems()).isEmpty();
        assertThat(p.rows()).hasSize(2);
        var a = p.rows().get(0);
        assertThat(a.errors()).isEmpty();
        assertThat(a.request().date()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(a.request().description()).isEqualTo("3 km WU & 20 min @ T");
        assertThat(a.request().notes()).isEqualTo("<ok>");
        assertThat(a.request().targetDistanceKm()).isEqualByComparingTo("12.5");
        assertThat(a.request().targetDurationMin()).isEqualTo(60);
        var b = p.rows().get(1);
        assertThat(b.row()).isEqualTo(3);
        assertThat(b.errors()).isEmpty();
        assertThat(b.request().date()).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(b.request().demand()).isEqualTo(Demand.SUPPORTING);
        assertThat(b.request().targetDistanceKm()).isEqualByComparingTo("8");
    }

    @Test
    void anXlsxNumberThatIsNotADateIsExplained() {
        List<List<Object>> sheet = List.of(Arrays.asList(PlanImportParser.COLUMNS.toArray()),
                Arrays.asList(-5, "easy"), Arrays.asList(46351, "tempo"));
        var rows = PlanImportParser.parse(XlsxFixture.build(sheet)).rows();
        assertThat(rows.get(0).errors()).containsExactly("date '-5' is not a date. Use DD/MM/YYYY, for example 25/11/2026");
        assertThat(rows.get(1).errors()).containsExactly("unknown type 'tempo'");
    }

    @Test
    void aSingleCsvRowHasTheSameMeaningAsTheMatchingXlsxRow() {
        var fromCsv = only(csv(HEADER + "25/11/2026,vo2max,key,Reps,5x1000,10,55,n\n"));
        var fromXlsx = only(XlsxFixture.build(List.of(Arrays.asList(PlanImportParser.COLUMNS.toArray()),
                Arrays.asList("25/11/2026", "vo2max", "key", "Reps", "5x1000", 10, 55, "n"))));
        assertThat(fromXlsx.request()).usingRecursiveComparison().ignoringFields("targetDistanceKm")
                .isEqualTo(fromCsv.request());
        assertThat(fromXlsx.request().targetDistanceKm()).isEqualByComparingTo(fromCsv.request().targetDistanceKm());
    }
}
