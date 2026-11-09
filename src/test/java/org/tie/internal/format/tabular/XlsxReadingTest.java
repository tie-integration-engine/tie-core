package org.tie.internal.format.tabular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.FileErrorType;
import org.tie.config.ColumnLocation;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.Layout;
import org.tie.config.TypeFormat;
import org.tie.config.XlsxCellValues;
import org.tie.config.XlsxOptions;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.Reading;

class XlsxReadingTest {

    @TempDir
    Path dir;

    private Path workbook(Consumer<Workbook> content) throws IOException {
        return workbook(new XSSFWorkbook(), content);
    }

    private Path workbook(Workbook workbook, Consumer<Workbook> content) throws IOException {
        Path file = Files.createTempFile(dir, "book", ".xlsx");
        try (workbook; OutputStream out = Files.newOutputStream(file)) {
            content.accept(workbook);
            workbook.write(out);
        }
        return file;
    }

    private static Layout passThrough() {
        return Layout.builder("l").allowedFormats(FileFormat.XLSX).build();
    }

    private static Layout layout(FieldDefinition.Builder... fields) {
        Layout.Builder layout = Layout.builder("l").allowedFormats(FileFormat.XLSX);
        for (FieldDefinition.Builder field : fields) {
            layout.field(field);
        }
        return layout.build();
    }

    private static CellStyle style(Workbook workbook, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    /** One sheet with a header row {@code value} and one data row built by {@code cell}. */
    private Path singleValue(Consumer<Cell> cell) throws IOException {
        return workbook(book -> {
            Sheet sheet = book.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("value");
            cell.accept(sheet.createRow(1).createCell(0));
        });
    }

    private static Map<String, String> onlyRecord(Reading reading) {
        assertThat(reading.records).hasSize(1);
        return reading.raw().getFirst();
    }

    private static final XlsxOptions FORMATTED = XlsxOptions.builder().cellValues(XlsxCellValues.FORMATTED).build();

    private static Layout passThrough(XlsxOptions options) {
        return Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(options).build();
    }

    private static Layout layout(XlsxOptions options, FieldDefinition.Builder... fields) {
        Layout.Builder layout = Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(options);
        for (FieldDefinition.Builder field : fields) {
            layout.field(field);
        }
        return layout.build();
    }

    /** One data row: header {@code names}, each cell a number with the matching format (null = no style). */
    private Path numbers(String[] names, double[] values, String[] formats) throws IOException {
        return workbook(book -> {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            Row data = sheet.createRow(1);
            for (int i = 0; i < names.length; i++) {
                header.createCell(i).setCellValue(names[i]);
                Cell cell = data.createCell(i);
                cell.setCellValue(values[i]);
                if (formats[i] != null) {
                    cell.setCellStyle(style(book, formats[i]));
                }
            }
        });
    }

    private Path formatTable() throws IOException {
        return numbers(new String[] {"cp", "pct", "amount", "date", "nif"},
                new double[] {8001, 0.25, 1234.5, 45366, 12345678},
                new String[] {"00000", "0%", "#,##0.00", "dd/mm/yyyy", "00000000\"A\""});
    }

    private Path formattedDateCell() throws IOException {
        return numbers(new String[] {"fecha"}, new double[] {45366}, new String[] {"dd/mm/yyyy"});
    }

    @Test
    @DisplayName("AC-239 without cellValues the cells are read normalized, as in 0.2.0")
    void canonicalTable() throws IOException {
        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, formatTable(), passThrough()))).containsExactly(
                entry("cp", "8001"), entry("pct", "0.25"), entry("amount", "1234.5"), entry("date", "2024-03-15"),
                entry("nif", "12345678"));
    }

    @Test
    @DisplayName("AC-240 with cellValues FORMATTED the cells are read as Excel displays them")
    void formattedTable() throws IOException {
        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, formatTable(), passThrough(FORMATTED)))).containsExactly(
                entry("cp", "08001"), entry("pct", "25%"), entry("amount", "1,234.50"), entry("date", "15/03/2024"),
                entry("nif", "12345678A"));
    }

    @Test
    @DisplayName("AC-241 FORMATTED uses the options' locale for separators, also in General")
    void formattedLocale() throws IOException {
        Path file = numbers(new String[] {"amount", "plain"}, new double[] {1234.5, 1234.5}, new String[] {"#,##0.00", null});
        XlsxOptions spanish = FORMATTED.toBuilder().locale(java.util.Locale.forLanguageTag("es-ES")).build();

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough(spanish))))
                .containsExactly(entry("amount", "1.234,50"), entry("plain", "1234,5"));
        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough(FORMATTED))))
                .containsExactly(entry("amount", "1,234.50"), entry("plain", "1234.5"));
    }

    @Test
    @DisplayName("AC-242 a FORMATTED date cell converts with the field's typeFormat")
    void formattedDateWithFormat() throws IOException {
        Reading reading = Reading.file(FileFormat.XLSX, formattedDateCell(), layout(FORMATTED,
                FieldDefinition.builder("fecha").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy"))));

        assertThat(reading.typed()).containsExactly(Map.of("fecha", LocalDate.of(2024, 3, 15)));
    }

    @Test
    @DisplayName("AC-243 FORMATTED has no canonical fallback: a date cell in a DATE field without format is INVALID_TYPE")
    void formattedHasNoCanonicalFallback() throws IOException {
        FieldDefinition.Builder field = FieldDefinition.builder("fecha").type(FieldType.DATE);

        org.tie.internal.engine.LineResult result = Reading.file(FileFormat.XLSX, formattedDateCell(), layout(FORMATTED, field))
                .results().getFirst();
        assertThat(result).isInstanceOfSatisfying(org.tie.internal.engine.LineResult.Rejected.class, rejected -> {
            assertThat(rejected.errors()).singleElement().satisfies(error -> {
                assertThat(error.code()).isEqualTo(org.tie.ErrorCode.INVALID_TYPE);
                assertThat(error.args()).containsEntry("value", "15/03/2024");
            });
        });
        assertThat(Reading.file(FileFormat.XLSX, formattedDateCell(), layout(field)).typed())
                .containsExactly(Map.of("fecha", LocalDate.of(2024, 3, 15)));
    }

    @Test
    @DisplayName("AC-244 FORMATTED dates respect date1904")
    void formattedDate1904() throws IOException {
        Path file = workbook(book -> {
            org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook ct = ((XSSFWorkbook) book).getCTWorkbook();
            (ct.isSetWorkbookPr() ? ct.getWorkbookPr() : ct.addNewWorkbookPr()).setDate1904(true);
            Sheet sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("fecha");
            Cell cell = sheet.createRow(1).createCell(0);
            cell.setCellValue(43904);
            cell.setCellStyle(style(book, "dd/mm/yyyy"));
        });

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough(FORMATTED))))
                .containsExactly(entry("fecha", "15/03/2024"));
    }

    @Test
    @DisplayName("AC-245 FORMATTED booleans are TRUE/FALSE; a BOOLEAN field still reads them")
    void formattedBooleans() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("s");
            header.createCell(1).setCellValue("b");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(true);
            data.createCell(1).setCellValue(true);
        });

        Reading reading = Reading.file(FileFormat.XLSX, file, layout(FORMATTED, FieldDefinition.builder("s"),
                FieldDefinition.builder("b").type(FieldType.BOOLEAN)));

        assertThat(reading.typed()).containsExactly(Map.of("s", "TRUE", "b", true));
    }

    @Test
    @DisplayName("AC-246 a formula is its cached value, formatted with FORMATTED; never the formula text")
    void formattedFormula() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("a");
            header.createCell(1).setCellValue("f");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1234.5);
            Cell formula = data.createCell(1);
            formula.setCellFormula("A2*2");
            formula.setCellStyle(style(book, "#,##0.00"));
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
        });

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough(FORMATTED)))).containsEntry("f", "2,469.00");
        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough()))).containsEntry("f", "2469");
    }

    @Test
    @DisplayName("AC-247 FORMATTED keeps text cells and writes long General numbers in scientific notation")
    void formattedTextAndGeneral() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("text");
            header.createCell(1).setCellValue("big");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("007");
            data.createCell(1).setCellValue(123456789012d);
        });

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough(FORMATTED))))
                .containsExactly(entry("text", "007"), entry("big", "1.23457E+11"));
    }

    @Test
    @DisplayName("AC-248 with FORMATTED the rawValues of a rejected line are the formatted texts")
    void formattedRawValues() throws IOException {
        org.tie.internal.engine.LineResult result = Reading.file(FileFormat.XLSX, formattedDateCell(),
                layout(FORMATTED, FieldDefinition.builder("fecha").type(FieldType.DATE))).results().getFirst();

        assertThat(result).isInstanceOfSatisfying(org.tie.internal.engine.LineResult.Rejected.class,
                rejected -> assertThat(rejected.rawValues()).containsExactly(entry("fecha", "15/03/2024")));
    }

    /** Header {@code a, b, c}; data rows 2 and 3; row 4 with blank cells " ", "", " "; data row 5. */
    private Path withBlankRow() throws IOException {
        return workbook(book -> {
            Sheet sheet = book.createSheet();
            String[][] rows = {{"a", "b", "c"}, {"1", "x", "y"}, {"2", "x", "y"}, {" ", "", " "}, {"3", "x", "y"}};
            for (int r = 0; r < rows.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
        });
    }

    @Test
    @DisplayName("AC-249 skipBlankRecords drops an XLSX row whose cells are all blank; the next keeps its row number")
    void skipBlankRows() throws IOException {
        Layout layout = Layout.builder("l").allowedFormats(FileFormat.XLSX)
                .xlsx(XlsxOptions.builder().skipBlankRecords(true).build()).build();

        Reading reading = Reading.file(FileFormat.XLSX, withBlankRow(), layout);

        assertThat(reading.records).extracting(org.tie.internal.format.ReadRecord::rowNumber).containsExactly(2, 3, 5);
        assertThat(reading.raw()).extracting(values -> values.get("a")).containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("AC-250 without skipBlankRecords the blank XLSX row is a record with blank values")
    void keepBlankRows() throws IOException {
        Reading reading = Reading.file(FileFormat.XLSX, withBlankRow(), passThrough());

        assertThat(reading.records).extracting(org.tie.internal.format.ReadRecord::rowNumber).containsExactly(2, 3, 4, 5);
        assertThat(reading.raw().get(2)).containsEntry("a", " ").containsEntry("c", " ");
    }

    @Test
    @DisplayName("AC-37 a formula is read as its cached value")
    void formulaCachedValue() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("value");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
        });

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough()))).containsExactly(entry("value", "2"));
    }

    @Test
    @DisplayName("AC-38 an XLSX above POI's compression ratio fails without being expanded")
    void zipBomb() throws IOException {
        Path valid = singleValue(cell -> cell.setCellValue("x"));
        Path bomb = dir.resolve("bomb.xlsx");
        try (ZipFile zip = new ZipFile(valid.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bomb))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] content = in.readAllBytes();
                    if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                        String xml = new String(content, StandardCharsets.UTF_8);
                        int root = xml.indexOf("<worksheet");
                        out.write(xml.substring(0, root).getBytes(StandardCharsets.UTF_8));
                        byte[] padding = " ".repeat(1 << 20).getBytes(StandardCharsets.UTF_8);
                        for (int i = 0; i < 50; i++) {
                            out.write(padding);
                        }
                        out.write(xml.substring(root).getBytes(StandardCharsets.UTF_8));
                    } else {
                        out.write(content);
                    }
                }
                out.closeEntry();
            }
        }

        assertThatThrownBy(() -> Reading.file(FileFormat.XLSX, bomb, passThrough()))
                .isInstanceOfSatisfying(FileFailure.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FileErrorType.UNSAFE_CONTENT);
                    assertThat(failure).hasMessageContaining("zip bomb");
                });
    }

    @Test
    @DisplayName("AC-144 an XLSX header at row 3 with data from row 4")
    void headerAfterPreamble() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Report");
            sheet.createRow(2).createCell(0).setCellValue("nif");
            sheet.createRow(3).createCell(0).setCellValue("1");
            sheet.createRow(4).createCell(0).setCellValue("2");
        });
        Layout layout = Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(XlsxOptions.builder().firstDataRow(4).build())
                .field(FieldDefinition.builder("nif")).build();

        Reading reading = Reading.file(FileFormat.XLSX, file, layout);

        assertThat(reading.inputs()).containsExactly(Map.of("nif", "1"), Map.of("nif", "2"));
        assertThat(reading.rows()).containsExactly(4, 5);
    }

    @Test
    @DisplayName("AC-165 a number formatted as currency is read as its stored value")
    void currencyFormattedNumber() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("amount");
            Cell cell = sheet.createRow(1).createCell(0);
            cell.setCellValue(1234.5);
            cell.setCellStyle(style(book, "#,##0.00 \"€\""));
        });

        Reading reading = Reading.file(FileFormat.XLSX, file, layout(FieldDefinition.builder("amount").type(FieldType.DECIMAL)));

        assertThat(reading.raw()).containsExactly(Map.of("amount", "1234.5"));
        assertThat(reading.typed()).containsExactly(Map.of("amount", new BigDecimal("1234.5")));
    }

    @Test
    @DisplayName("AC-166 an integer number is read without decimals")
    void integerNumber() throws IOException {
        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, singleValue(cell -> cell.setCellValue(42)), passThrough())))
                .containsExactly(entry("value", "42"));
    }

    @Test
    @DisplayName("AC-167 a date cell is ISO text and also valid for a DATE field with another typeFormat")
    void dateCell() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("born");
            header.createCell(1).setCellValue("text");
            Row row = sheet.createRow(1);
            for (int column = 0; column < 2; column++) {
                Cell cell = row.createCell(column);
                cell.setCellValue(LocalDate.of(2026, 12, 31));
                cell.setCellStyle(style(book, "dd/mm/yyyy"));
            }
        });
        Layout layout = layout(
                FieldDefinition.builder("born").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy")),
                FieldDefinition.builder("text"));

        assertThat(Reading.file(FileFormat.XLSX, file, layout).typed())
                .containsExactly(Map.of("born", LocalDate.of(2026, 12, 31), "text", "2026-12-31"));
    }

    @Test
    @DisplayName("AC-168 a date cell with time is read as ISO date-time")
    void dateTimeCell() throws IOException {
        Path file = singleValue(cell -> {
            cell.setCellValue(LocalDateTime.of(2026, 12, 31, 10, 0));
            cell.setCellStyle(style(cell.getSheet().getWorkbook(), "dd/mm/yyyy hh:mm"));
        });

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough())))
                .containsExactly(entry("value", "2026-12-31T10:00:00"));
    }

    @Test
    void booleansStringsAndGaps() throws IOException {
        Path file = workbook(book -> {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            for (int column = 0; column < 4; column++) {
                header.createCell(column).setCellValue("c" + column);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(true);
            row.createCell(1).setCellValue(" José ");
            row.createCell(3).setCellValue(-0.25);
            sheet.createRow(2).createCell(0).setCellStyle(style(book, "0.00"));
            sheet.createRow(3).createCell(1).setCellValue("z");
        });

        Reading reading = Reading.file(FileFormat.XLSX, file, passThrough());

        assertThat(reading.raw()).containsExactly(
                Map.of("c0", "true", "c1", " José ", "c2", "", "c3", "-0.25"),
                Map.of("c0", "", "c1", "z"));
        assertThat(reading.rows()).containsExactly(2, 4);
    }

    @Test
    void inlineStringsFromStreamingWorkbooks() throws IOException {
        Path file = workbook(new SXSSFWorkbook(), book -> {
            Sheet sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("name");
            sheet.createRow(1).createCell(0).setCellValue("inline");
        });

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, passThrough()))).containsExactly(entry("name", "inline"));
    }

    @Test
    void sheetSelection() throws IOException {
        Path file = workbook(book -> {
            book.createSheet("First").createRow(0).createCell(0).setCellValue("first");
            Sheet second = book.createSheet("Second");
            second.createRow(0).createCell(0).setCellValue("v");
            second.createRow(1).createCell(0).setCellValue("second");
        });
        Layout second = Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(XlsxOptions.builder().sheet("Second").build()).build();
        Layout missing = Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(XlsxOptions.builder().sheet("Nope").build()).build();

        assertThat(onlyRecord(Reading.file(FileFormat.XLSX, file, second))).containsExactly(entry("v", "second"));
        assertThat(Reading.file(FileFormat.XLSX, file, passThrough()).records).isEmpty();
        assertThatThrownBy(() -> Reading.file(FileFormat.XLSX, file, missing))
                .isInstanceOfSatisfying(FileFailure.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
                    assertThat(failure).hasMessage("sheet 'Nope' not found (sheets: First, Second)");
                });
    }

    @Test
    void letterColumnsWithoutHeader() throws IOException {
        Path file = workbook(book -> {
            Row row = book.createSheet().createRow(0);
            row.createCell(0).setCellValue("a");
            row.createCell(27).setCellValue("ab");
        });
        Layout layout = Layout.builder("l").allowedFormats(FileFormat.XLSX).columnLocation(ColumnLocation.LETTER)
                .xlsx(XlsxOptions.builder().hasHeader(false).firstDataRow(1).build())
                .field(FieldDefinition.builder("first").column("A"))
                .field(FieldDefinition.builder("far").column("AB"))
                .build();

        assertThat(Reading.file(FileFormat.XLSX, file, layout).inputs()).containsExactly(Map.of("first", "a", "far", "ab"));
    }

    @Test
    void notAnXlsxFileIsStructural() throws IOException {
        Path file = Files.writeString(dir.resolve("fake.xlsx"), "not a zip");

        assertThatThrownBy(() -> Reading.file(FileFormat.XLSX, file, passThrough()))
                .isInstanceOfSatisfying(FileFailure.class,
                        failure -> assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL));
    }

    @Test
    void xlsxCannotBeReadFromAStream() {
        assertThatThrownBy(() -> Reading.bytes(FileFormat.XLSX, new byte[0], passThrough()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
