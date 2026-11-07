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
        Layout layout = Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(new XlsxOptions(null, true, 4))
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
        Layout second = Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(new XlsxOptions("Second", true, 2)).build();
        Layout missing = Layout.builder("l").allowedFormats(FileFormat.XLSX).xlsx(new XlsxOptions("Nope", true, 2)).build();

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
                .xlsx(new XlsxOptions(null, false, 1))
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
