package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tie.internal.run.ImportHarness.csv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.FileStatus;
import org.tie.ImportResult;
import org.tie.ReportRef;
import org.tie.ResultStatus;
import org.tie.WarningCode;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnEmpty;
import org.tie.config.OnExistingFile;
import org.tie.config.ReportMode;
import org.tie.config.ResultReport;
import org.tie.config.Validations;
import org.tie.spi.Outcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReportTest {

    @TempDir
    Path root;

    private ImportHarness h;
    private Path reports;

    @BeforeEach
    void setUp() throws IOException {
        h = new ImportHarness(root);
        reports = Files.createDirectories(root.resolve("reports"));
    }

    private ResultReport report(ReportMode mode, FileFormat format) {
        return new ResultReport(mode, format, LocalDirectoryChannel.of(reports), "{sourceName}_{layout}_result");
    }

    /** A CSV import of {@code nif;sexo} where sexo only accepts M. */
    private IntegrationDefinition.Builder people(ResultReport report) {
        return h.importOf(Layout.builder("alta").fileNamePattern("alta_{timestamp:yyyyMMddHHmmss}")
                        .allowedFormats(FileFormat.CSV).csv(org.tie.config.CsvOptions.builder().separator(';').build())
                        .field(FieldDefinition.builder("nif"))
                        .field(FieldDefinition.builder("sexo").translation("M", "Male")))
                .resultReport(report);
    }

    private String reportText(String name) throws IOException {
        return Files.readString(reports.resolve(name));
    }

    private List<String> reportFiles() throws IOException {
        try (Stream<Path> files = Files.list(reports)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("AC-87 a REJECTED CSV report has the original values plus LINE_NUMBER, RESULT and REASON")
    void rejectedCsv() throws IOException {
        h.write("alta_20261026120000.csv", "nif;sexo\n1;X\n2;M\n3;Y\n4;Z\n");

        ImportResult result = h.run(people(report(ReportMode.REJECTED, FileFormat.CSV)));

        assertThat(reportText("alta_20261026120000_alta_result.csv")).isEqualTo("""
                nif,sexo,LINE_NUMBER,RESULT,REASON,CODES\r
                1,X,1,REJECTED,sexo: value 'X' has no translation,NO_TRANSLATION\r
                3,Y,3,REJECTED,sexo: value 'Y' has no translation,NO_TRANSLATION\r
                4,Z,4,REJECTED,sexo: value 'Z' has no translation,NO_TRANSLATION\r
                """);
        assertThat(result.reports()).singleElement().satisfies(ref -> {
            assertThat(ref.status()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(ref.fileName()).isEqualTo("alta_20261026120000_alta_result.csv");
            assertThat(ref.destination()).isEqualTo(reports.toString());
            assertThat(ref.sourceFileName()).isEqualTo("alta_20261026120000.csv");
        });
        assertThat(h.filesIn(h.work)).isEmpty();
    }

    @Test
    @DisplayName("AC-88 REJECTED_AND_APPLIED also lists the applied lines")
    void rejectedAndApplied() throws IOException {
        h.write("alta_20261026120000.csv", "nif;sexo\n1;X\n2;M\n");

        h.run(people(report(ReportMode.REJECTED_AND_APPLIED, FileFormat.CSV)));

        assertThat(reportText("alta_20261026120000_alta_result.csv")).contains(
                "1,X,1,REJECTED,sexo: value 'X' has no translation,NO_TRANSLATION\r\n",
                "2,M,2,APPLIED,,\r\n");
    }

    @Test
    @DisplayName("AC-89 AC-188 values that look like formulas are neutralized in CSV and XLSX, not in JSON")
    void formulaInjection() throws IOException {
        h.write("alta_20261026120000.csv", "nif;sexo\n=HYPERLINK(\"x\");X\n+1;X\n-1;X\n@cmd;X\n\"\tx\";X\n");

        h.run(people(report(ReportMode.REJECTED, FileFormat.CSV)));
        h.write("alta_20261027120000.csv", "nif;sexo\n=HYPERLINK(\"x\");X\n+1;X\n");
        h.run(people(report(ReportMode.REJECTED, FileFormat.XLSX)));
        h.write("alta_20261028120000.csv", "nif;sexo\n=HYPERLINK(\"x\");X\n+1;X\n");
        h.run(people(report(ReportMode.REJECTED, FileFormat.JSON)));

        assertThat(reportText("alta_20261026120000_alta_result.csv")).contains("\"'=HYPERLINK(\"\"x\"\")\"", "'+1,", "'-1,",
                "'@cmd,", "'\tx");
        try (InputStream in = Files.newInputStream(reports.resolve("alta_20261027120000_alta_result.xlsx"));
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=HYPERLINK(\"x\")");
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo("'+1");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getNumericCellValue()).isEqualTo(1);
        }
        JsonNode json = JsonMapper.builder().build().readTree(reportText("alta_20261028120000_alta_result.json"));
        assertThat(json.get(0).get("values").get("nif").stringValue()).isEqualTo("=HYPERLINK(\"x\")");
        assertThat(json.get(1).get("values").get("nif").stringValue()).isEqualTo("+1");
    }

    @Test
    @DisplayName("AC-90 AC-185 a report that cannot be delivered is a warning; the file stays applied")
    void reportDeliveryFails() throws IOException {
        Files.writeString(reports.resolve("alta_20261026120000_alta_result.csv"), "previous");
        h.write("alta_20261026120000.csv", "nif;sexo\n1;X\n");

        ImportResult result = h.run(people(new ResultReport(ReportMode.REJECTED, FileFormat.CSV,
                new LocalDirectoryChannel(reports, null, OnExistingFile.FAIL), "{sourceName}_{layout}_result"))
                .duplicateProtection(new org.tie.config.DuplicateProtection(true, null)));

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.SUCCESS);
        assertThat(result.files().getFirst().warnings()).singleElement()
                .satisfies(w -> assertThat(w.code()).isEqualTo(WarningCode.REPORT_FAILED));
        assertThat(result.reports()).singleElement().extracting(ReportRef::status).isEqualTo(ResultStatus.FAILED);
        assertThat(reportText("alta_20261026120000_alta_result.csv")).isEqualTo("previous");
        assertThat(result.watermarks()).containsEntry("alta", java.time.LocalDateTime.of(2026, 10, 26, 12, 0));
    }

    @Test
    @DisplayName("AC-91 without a report the listener still receives every rejection")
    void noReport() throws IOException {
        h.write("alta_20261026120000.csv", "nif;sexo\n1;X\n2;Y\n");

        ImportResult result = h.run(people(ResultReport.NONE));

        assertThat(reportFiles()).isEmpty();
        assertThat(result.reports()).isEmpty();
        assertThat(h.rejections).hasSize(2);
    }

    @Test
    @DisplayName("AC-107 one report per input file and layout")
    void onePerFileAndLayout() throws IOException {
        Layout.Builder order = Layout.builder("order").fileNamePattern("orders_{timestamp:yyyyMMdd}")
                .allowedFormats(FileFormat.JSON).field(FieldDefinition.builder("id").onEmpty(OnEmpty.ERROR));
        Layout.Builder line = Layout.builder("line").parent("order", "lines").field(FieldDefinition.builder("sku"));
        h.write("orders_20261026.json", "[{\"lines\":[{\"sku\":\"a\"}]}]");
        h.write("orders_20261027.json", "[{\"lines\":[{\"sku\":\"b\"}]}]");

        ImportResult result = h.run(h.importOf(order, line).resultReport(report(ReportMode.REJECTED, FileFormat.JSON)));

        assertThat(reportFiles()).containsExactly("orders_20261026_line_result.json", "orders_20261026_order_result.json",
                "orders_20261027_line_result.json", "orders_20261027_order_result.json");
        assertThat(result.reports()).hasSize(4);
    }

    @Test
    @DisplayName("AC-186 REASON joins every error and CODES lists their codes")
    void reasonAndCodes() throws IOException {
        h.write("alta_20261026120000.csv", "a;b\nabcdef;X\n");

        h.run(h.importOf(Layout.builder("alta").fileNamePattern("alta_{timestamp:yyyyMMddHHmmss}")
                        .allowedFormats(FileFormat.CSV).csv(org.tie.config.CsvOptions.builder().separator(';').build())
                        .field(FieldDefinition.builder("a").validations(Validations.maxLength(5)))
                        .field(FieldDefinition.builder("b").translation("M", "Male")))
                .resultReport(report(ReportMode.REJECTED, FileFormat.CSV)));

        assertThat(reportText("alta_20261026120000_alta_result.csv")).endsWith(
                "abcdef,X,1,REJECTED,a: value is longer than 5 | b: value 'X' has no translation,"
                        + "\"TOO_LONG,NO_TRANSLATION\"\r\n");
    }

    @Test
    @DisplayName("AC-187 JSON and XML reports carry the errors as a list of {field, code, args, message}")
    void structuredErrors() throws IOException {
        h.write("alta_20261026120000.csv", "nif;sexo\n1;X\n");
        h.behaviour = record -> Outcome.APPLIED;

        h.run(people(report(ReportMode.REJECTED, FileFormat.JSON)));
        h.write("alta_20261027120000.csv", "nif;sexo\n1;X\n");
        h.run(people(report(ReportMode.REJECTED, FileFormat.XML)));

        JsonNode line = JsonMapper.builder().build().readTree(reportText("alta_20261026120000_alta_result.json")).get(0);
        assertThat(line.get("LINE_NUMBER").intValue()).isEqualTo(1);
        assertThat(line.get("RESULT").stringValue()).isEqualTo("REJECTED");
        assertThat(line.get("values").get("sexo").stringValue()).isEqualTo("X");
        JsonNode error = line.get("errors").get(0);
        assertThat(error.get("field").stringValue()).isEqualTo("sexo");
        assertThat(error.get("code").stringValue()).isEqualTo("NO_TRANSLATION");
        assertThat(error.get("args").get("value").stringValue()).isEqualTo("X");
        assertThat(error.get("message").stringValue()).isEqualTo("value 'X' has no translation");
        assertThat(reportText("alta_20261027120000_alta_result.xml")).contains(
                "<line number=\"1\" result=\"REJECTED\"><reason>sexo: value 'X' has no translation</reason>",
                "<value name=\"nif\">1</value>",
                "<error code=\"NO_TRANSLATION\" field=\"sexo\"><message>value 'X' has no translation</message>"
                        + "<arg name=\"value\">X</arg></error>");
    }

    @Test
    void noReportForFailedFilesOrLayoutsWithoutLines() throws IOException {
        h.write("alta_20261026120000.csv", "nif;sexo\n1;M\n");
        h.write("alta_20261027120000.csv", "nif;sexo\n1;X\n\"unclosed\n");

        ImportResult result = h.run(people(report(ReportMode.REJECTED, FileFormat.CSV)));

        assertThat(result.files()).extracting(f -> f.status()).containsExactly(FileStatus.SUCCESS, FileStatus.FAILED);
        assertThat(reportFiles()).isEmpty();
        assertThat(h.filesIn(h.work)).isEmpty();
    }
}
