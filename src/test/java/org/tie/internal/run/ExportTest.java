package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.CancellationToken;
import org.tie.ConfigurationException;
import org.tie.ErrorCode;
import org.tie.ExportContext;
import org.tie.ExportResult;
import org.tie.FileErrorType;
import org.tie.ImportContext;
import org.tie.ResultStatus;
import org.tie.Tie;
import org.tie.config.ColumnLocation;
import org.tie.config.CopyMode;
import org.tie.config.DefaultMode;
import org.tie.config.Direction;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnEmpty;
import org.tie.config.OnExistingFile;
import org.tie.config.OutputAs;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.RejectionThreshold;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.TypeFormat;
import org.tie.config.XmlOptions;
import org.tie.spi.Outcome;

class ExportTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String NAME = "out_20261026120000";

    @TempDir
    Path root;

    private Path out;
    private Path work;
    private Tie.Builder tie;

    @BeforeEach
    void setUp() throws IOException {
        out = Files.createDirectories(root.resolve("out"));
        work = Files.createDirectories(root.resolve("work"));
        tie = Tie.builder().clock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Layout.Builder layout(FileFormat format, FieldDefinition.Builder... fields) {
        Layout.Builder layout = Layout.builder("out").allowedFormats(format);
        for (FieldDefinition.Builder field : fields) {
            layout.field(field);
        }
        return layout;
    }

    private IntegrationDefinition.Builder export(Layout.Builder... layouts) {
        IntegrationDefinition.Builder definition = IntegrationDefinition.exporting("e")
                .outputChannel(LocalDirectoryChannel.of(out))
                .outputNamePattern("out_{timestamp:yyyyMMddHHmmss}");
        for (Layout.Builder layout : layouts) {
            definition.layout(layout);
        }
        return definition;
    }

    @SafeVarargs
    private ExportResult run(IntegrationDefinition.Builder definition, Map<String, Object>... records) {
        return run(definition, ExportContext.builder().workingDirectory(work), records);
    }

    @SafeVarargs
    @SuppressWarnings("varargs") // the array is only read
    private ExportResult run(IntegrationDefinition.Builder definition, ExportContext.Builder context,
                             Map<String, Object>... records) {
        return tie.build().exportRecords(definition.build(), java.util.Arrays.stream(records).toList().iterator(), context.build());
    }

    private static Map<String, Object> record(Object... namesAndValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            record.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return record;
    }

    private String written(String extension) throws IOException {
        return Files.readString(out.resolve(NAME + "." + extension));
    }

    private List<String> filesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("AC-92 a DATE with dd/MM/yyyy is written as 31/12/2026")
    void dateWithFormat() throws IOException {
        run(export(layout(FileFormat.CSV, FieldDefinition.builder("d").type(FieldType.DATE)
                .typeFormat(TypeFormat.date("dd/MM/yyyy")))), record("d", LocalDate.of(2026, 12, 31)));

        assertThat(written("csv")).isEqualTo("d\r\n31/12/2026\r\n");
    }

    @Test
    @DisplayName("AC-93 a BOTH translation is inverted on export")
    void bothTranslationInverted() throws IOException {
        run(export(layout(FileFormat.CSV, FieldDefinition.builder("sex").translation("M", "Male"))), record("sex", "Male"));

        assertThat(written("csv")).isEqualTo("sex\r\nM\r\n");
    }

    @Test
    @DisplayName("AC-94 an IN translation does not apply on export: onUnmatched decides")
    void inTranslationIgnored() {
        ExportResult result = run(export(layout(FileFormat.CSV, FieldDefinition.builder("sex")
                .translation(new Translation("M", "Male", TranslationDirection.IN)))), record("sex", "Male"));

        assertThat(result.rejected()).singleElement()
                .satisfies(r -> assertThat(r.errors().getFirst().code()).isEqualTo(ErrorCode.NO_TRANSLATION));
    }

    @Test
    @DisplayName("AC-95 KEEP and an absent value write an empty cell")
    void keepIsEmpty() throws IOException {
        run(export(layout(FileFormat.CSV, FieldDefinition.builder("a"), FieldDefinition.builder("b").onEmpty(OnEmpty.KEEP))),
                record("a", "x"));

        assertThat(written("csv")).isEqualTo("a,b\r\nx,\r\n");
    }

    @Test
    @DisplayName("AC-96 zero records: header only / [] / empty root, and it is delivered")
    void zeroRecords() throws IOException {
        assertThat(run(export(layout(FileFormat.CSV, FieldDefinition.builder("a")))).status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(written("csv")).isEqualTo("a\r\n");
        run(export(layout(FileFormat.JSON, FieldDefinition.builder("a"))));
        assertThat(written("json")).isEqualTo("[]");
        run(export(layout(FileFormat.XML, FieldDefinition.builder("a")).xml(XmlOptions.of("rec"))));
        assertThat(written("xml")).matches("<\\?xml version=\"1.0\" encoding=\"UTF-8\"\\?><records>\\s*</records>");
    }

    @Test
    @DisplayName("AC-97 maxRejected = 0 and an invalid record: FAILED and nothing delivered")
    void thresholdDeliversNothing() throws IOException {
        ExportResult result = run(export(layout(FileFormat.CSV, FieldDefinition.builder("n").type(FieldType.INTEGER)))
                        .rejectionThreshold(RejectionThreshold.maxRejected(0)),
                record("n", 1L), record("n", "x"), record("n", 3L));

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.error().type()).isEqualTo(FileErrorType.THRESHOLD);
        assertThat(result.fileName()).isNull();
        assertThat(filesIn(out)).isEmpty();
        assertThat(filesIn(work)).isEmpty();
    }

    @Test
    @DisplayName("AC-98 an export never neutralizes formulas")
    void formulasAsIs() throws IOException {
        run(export(layout(FileFormat.CSV, FieldDefinition.builder("f"))), record("f", "=1+1"));

        assertThat(written("csv")).isEqualTo("f\r\n=1+1\r\n");
    }

    @Test
    @DisplayName("AC-99 the output name comes from the pattern and the injected clock")
    void outputName() {
        ExportResult result = run(export(layout(FileFormat.CSV, FieldDefinition.builder("a"))), record("a", "x"));

        assertThat(result.fileName()).isEqualTo("out_20261026120000.csv");
        assertThat(result.destination()).isEqualTo(out.toString());
        assertThat(result.written()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-173 a transformer receives IMPORT when importing and EXPORT when exporting")
    void transformerDirection() throws IOException {
        List<Direction> seen = new ArrayList<>();
        tie.transformer("trace", (value, record, direction) -> {
            seen.add(direction);
            return value;
        });
        run(export(layout(FileFormat.CSV, FieldDefinition.builder("a").transformer("trace"))), record("a", "x"));
        Path in = Files.createDirectories(root.resolve("in"));
        Files.writeString(in.resolve("a.csv"), "a\nx\n");

        tie.build().importFiles(IntegrationDefinition.importing("i").inputChannel(LocalDirectoryChannel.of(in))
                        .layout(Layout.builder("a").fileNamePattern("a").allowedFormats(FileFormat.CSV)
                                .field(FieldDefinition.builder("a").transformer("trace"))).build(),
                ImportContext.builder(record -> Outcome.APPLIED).workingDirectory(work).build());

        assertThat(seen).containsExactly(Direction.EXPORT, Direction.IMPORT);
    }

    @Test
    @DisplayName("AC-176 JSON numbers are native unless outputAs TEXT")
    void jsonNativeAndText() throws IOException {
        run(export(layout(FileFormat.JSON,
                FieldDefinition.builder("n").type(FieldType.INTEGER),
                FieldDefinition.builder("t").type(FieldType.INTEGER).outputAs(OutputAs.TEXT),
                FieldDefinition.builder("d").type(FieldType.DECIMAL),
                FieldDefinition.builder("b").type(FieldType.BOOLEAN),
                FieldDefinition.builder("day").type(FieldType.DATE))),
                record("n", 42L, "t", 42, "d", new BigDecimal("1.50"), "b", true, "day", LocalDate.of(2026, 12, 31)));

        assertThat(written("json")).isEqualTo("[{\"n\":42,\"t\":\"42\",\"d\":1.50,\"b\":true,\"day\":\"2026-12-31\"}]");
    }

    @Test
    @DisplayName("AC-255 a date pattern with locale es-ES writes Spanish month names")
    void spanishMonthOnExport() throws IOException {
        run(export(layout(FileFormat.CSV, FieldDefinition.builder("day").type(FieldType.DATE)
                .typeFormat(TypeFormat.date("dd-MMM-yyyy", java.util.Locale.forLanguageTag("es-ES"))))),
                record("day", LocalDate.of(2026, 12, 31)));

        assertThat(written("csv")).isEqualTo("day\r\n31-dic-2026\r\n");
    }

    @Test
    @DisplayName("AC-238 defaultMode is ignored on export")
    void defaultModeIgnoredOnExport() throws IOException {
        Map<String, Object> record = record("day", null);
        FieldDefinition.Builder day = FieldDefinition.builder("day").type(FieldType.DATE)
                .typeFormat(TypeFormat.date("dd/MM/yyyy")).defaultValue("31/12/2026");

        run(export(layout(FileFormat.CSV, day)), record);
        String withoutMode = written("csv");
        Files.delete(out.resolve(NAME + ".csv"));
        run(export(layout(FileFormat.CSV, day.defaultMode(DefaultMode.TEXT))), record);

        assertThat(written("csv")).isEqualTo(withoutMode);
    }

    @Test
    @DisplayName("AC-226 copyMode is ignored on export: a copy takes the source's supplied value")
    void copyModeIgnoredOnExport() throws IOException {
        FieldDefinition.Builder day = FieldDefinition.builder("day").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy"));
        Map<String, Object> record = record("day", LocalDate.of(2026, 12, 31));

        run(export(layout(FileFormat.CSV, day, FieldDefinition.builder("copy").copyFrom("day"))), record);
        String withoutMode = written("csv");
        Files.delete(out.resolve(NAME + ".csv"));
        run(export(layout(FileFormat.CSV, day,
                FieldDefinition.builder("copy").copyFrom("day").copyMode(CopyMode.CANONICAL))), record);

        assertThat(written("csv")).isEqualTo(withoutMode);
    }

    @Test
    @DisplayName("AC-253 the XLSX reading options are ignored on export")
    void xlsxReadingOptionsIgnoredOnExport() throws IOException {
        Map<String, Object> record = record("n", 42L, "day", LocalDate.of(2026, 12, 31), "t", " ");
        FieldDefinition.Builder[] fields = {FieldDefinition.builder("n").type(FieldType.INTEGER),
                FieldDefinition.builder("day").type(FieldType.DATE), FieldDefinition.builder("t")};

        run(export(layout(FileFormat.XLSX, fields)), record);
        byte[] plain = sheetXml(out.resolve(NAME + ".xlsx"));
        Files.delete(out.resolve(NAME + ".xlsx"));
        run(export(layout(FileFormat.XLSX, fields).xlsx(org.tie.config.XlsxOptions.builder().skipBlankRecords(true)
                .cellValues(org.tie.config.XlsxCellValues.FORMATTED).locale(java.util.Locale.forLanguageTag("es-ES")).build())),
                record);

        assertThat(sheetXml(out.resolve(NAME + ".xlsx"))).isEqualTo(plain);
    }

    private static byte[] sheetXml(Path xlsx) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(xlsx.toFile())) {
            java.util.zip.ZipEntry sheet = zip.getEntry("xl/worksheets/sheet1.xml");
            try (InputStream in = zip.getInputStream(sheet)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    @DisplayName("AC-177 XLSX dates are date cells unless they have a typeFormat")
    void xlsxDates() throws IOException {
        run(export(layout(FileFormat.XLSX,
                FieldDefinition.builder("native").type(FieldType.DATE),
                FieldDefinition.builder("text").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy")),
                FieldDefinition.builder("amount").type(FieldType.DECIMAL))),
                record("native", LocalDate.of(2026, 12, 31), "text", LocalDate.of(2026, 12, 31), "amount", new BigDecimal("2.5")));

        try (InputStream in = Files.newInputStream(out.resolve(NAME + ".xlsx")); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("native");
            Cell nativeDate = workbook.getSheetAt(0).getRow(1).getCell(0);
            Cell text = workbook.getSheetAt(0).getRow(1).getCell(1);
            Cell amount = workbook.getSheetAt(0).getRow(1).getCell(2);
            assertThat(nativeDate.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(nativeDate)).isTrue();
            assertThat(nativeDate.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 12, 31, 0, 0));
            assertThat(text.getStringCellValue()).isEqualTo("31/12/2026");
            assertThat(amount.getNumericCellValue()).isEqualTo(2.5);
        }
    }

    @Test
    @DisplayName("AC-179 dotted paths nest in JSON; @id is an attribute in XML")
    void pathsInJsonAndXml() throws IOException {
        run(export(layout(FileFormat.JSON, FieldDefinition.builder("nif").column("cliente.nif"),
                FieldDefinition.builder("cliente.nombre"))), record("nif", "1", "cliente.nombre", "Ana"));
        run(export(layout(FileFormat.XML, FieldDefinition.builder("id").column("@id"),
                FieldDefinition.builder("city").column("address.city"), FieldDefinition.builder("cur").column("amount.@currency"),
                FieldDefinition.builder("amount")).xml(XmlOptions.of("rec"))),
                record("id", "7", "city", "Madrid", "cur", "EUR", "amount", "12"));

        assertThat(written("json")).isEqualTo("[{\"cliente\":{\"nif\":\"1\",\"nombre\":\"Ana\"}}]");
        assertThat(written("xml")).contains("<rec id=\"7\"><address><city>Madrid</city></address>"
                + "<amount currency=\"EUR\">12</amount></rec>");
    }

    @Test
    @DisplayName("AC-180 LETTER columns leave the letters without a field empty")
    void letterColumns() throws IOException {
        run(export(layout(FileFormat.CSV, FieldDefinition.builder("a").column("A"), FieldDefinition.builder("c").column("C"))
                .columnLocation(ColumnLocation.LETTER)), record("a", "x", "c", "z"));

        assertThat(written("csv")).isEqualTo("a,,c\r\nx,,z\r\n");
    }

    @Test
    @DisplayName("AC-181 an empty value is null in JSON and an empty element in XML")
    void emptyValues() throws IOException {
        run(export(layout(FileFormat.JSON, FieldDefinition.builder("a"), FieldDefinition.builder("b"))), record("a", "x"));
        run(export(layout(FileFormat.XML, FieldDefinition.builder("a"), FieldDefinition.builder("@b")).xml(XmlOptions.of("r"))),
                record("a", ""));

        assertThat(written("json")).isEqualTo("[{\"a\":\"x\",\"b\":null}]");
        assertThat(written("xml")).contains("<r><a/></r>");
    }

    @Test
    @DisplayName("AC-182 an existing name: SUFFIX (default) / FAIL / OVERWRITE")
    void collisions() throws IOException {
        Files.writeString(out.resolve(NAME + ".csv"), "previous");
        IntegrationDefinition.Builder definition = export(layout(FileFormat.CSV, FieldDefinition.builder("a")));

        ExportResult suffix = run(definition, record("a", "x"));
        ExportResult fail = run(definition.outputChannel(new LocalDirectoryChannel(out, null, OnExistingFile.FAIL)), record("a", "x"));

        assertThat(suffix.fileName()).isEqualTo(NAME + "." + NOW.toEpochMilli() + ".csv");
        assertThat(fail.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(fail.error().type()).isEqualTo(FileErrorType.NAME_COLLISION);
        assertThat(Files.readString(out.resolve(NAME + ".csv"))).isEqualTo("previous");
        ExportResult overwrite = run(definition.outputChannel(new LocalDirectoryChannel(out, null, OnExistingFile.OVERWRITE)),
                record("a", "x"));
        assertThat(overwrite.fileName()).isEqualTo(NAME + ".csv");
        assertThat(written("csv")).isEqualTo("a\r\nx\r\n");
        assertThat(filesIn(out)).containsExactly(NAME + "." + NOW.toEpochMilli() + ".csv", NAME + ".csv");
    }

    @Test
    @DisplayName("AC-184 a cancelled export delivers nothing")
    void cancellation() throws IOException {
        CancellationToken token = new CancellationToken();
        Iterator<Map<String, Object>> records = new Iterator<>() {
            int n;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Map<String, Object> next() {
                if (++n == 3) {
                    token.cancel();
                }
                return record("a", "x" + n);
            }
        };

        ExportResult result = tie.build().exportRecords(export(layout(FileFormat.CSV, FieldDefinition.builder("a"))).build(),
                records, ExportContext.builder().workingDirectory(work).cancellation(token).build());

        assertThat(result.status()).isEqualTo(ResultStatus.CANCELLED);
        assertThat(result.written()).isEqualTo(3);
        assertThat(filesIn(out)).isEmpty();
        assertThat(filesIn(work)).isEmpty();
    }

    @Test
    void nestedRecordsAndRejectedChildren() throws IOException {
        Map<String, Object> order = record("id", 1L, "lines", List.of(record("sku", "A"), record("sku", ""), record("sku", "C")));

        ExportResult result = run(export(layout(FileFormat.JSON, FieldDefinition.builder("id").type(FieldType.INTEGER)),
                Layout.builder("line").parent("out", "lines").field(FieldDefinition.builder("sku").onEmpty(OnEmpty.ERROR))), order);

        assertThat(written("json")).isEqualTo("[{\"id\":1,\"lines\":[{\"sku\":\"A\"},{\"sku\":\"C\"}]}]");
        assertThat(result.rejected()).singleElement().satisfies(rejection -> {
            assertThat(rejection.lineNumber()).isEqualTo(3);
            assertThat(rejection.parentLineNumber()).isEqualTo(1);
            assertThat(rejection.layout()).isEqualTo("line");
        });
        assertThat(result.written()).isEqualTo(3);
    }

    @Test
    void typedValuesAreCoercedAndChecked() {
        ExportResult result = run(export(layout(FileFormat.CSV,
                        FieldDefinition.builder("n").type(FieldType.INTEGER),
                        FieldDefinition.builder("d").type(FieldType.DECIMAL).typeFormat(TypeFormat.decimal(',', '.')))),
                record("n", 7, "d", 1234567.5), record("n", "8", "d", "1.5"), record("n", LocalDate.of(2026, 1, 1), "d", "x"));

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.rejected()).singleElement().satisfies(rejection -> {
            assertThat(rejection.errors()).extracting(e -> e.field() + " " + e.code())
                    .containsExactly("n INVALID_TYPE", "d INVALID_TYPE");
            assertThat(rejection.rawValues()).containsEntry("n", "2026-01-01");
        });
    }

    @Test
    void groupedDecimalsAndBooleanLiterals() throws IOException {
        run(export(layout(FileFormat.CSV,
                FieldDefinition.builder("d").type(FieldType.DECIMAL).typeFormat(TypeFormat.decimal(',', '.')),
                FieldDefinition.builder("b").type(FieldType.BOOLEAN).typeFormat(TypeFormat.booleanLiterals("S", "N")))
                .formatOptions(org.tie.config.FormatOptions.DEFAULTS.withCsv(org.tie.config.CsvOptions.builder().separator(';').build()))),
                record("d", new BigDecimal("-1234567.891"), "b", false));

        assertThat(written("csv")).isEqualTo("d;b\r\n-1.234.567,891;N\r\n");
    }

    @Test
    void providedOutputStream() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        ExportResult result = run(export(layout(FileFormat.CSV, FieldDefinition.builder("a")))
                        .outputChannel(ProvidedStreamChannel.INSTANCE),
                ExportContext.builder().workingDirectory(work).providedOutput(() -> stream), record("a", "x"));

        assertThat(result.destination()).isEqualTo("provided output stream");
        assertThat(stream.toString(StandardCharsets.UTF_8)).isEqualTo("a\r\nx\r\n");
    }

    @Test
    void configurationIsValidated() {
        IntegrationDefinition definition = export(layout(FileFormat.CSV, FieldDefinition.builder("a")))
                .outputNamePattern("out_{tenant}").outputChannel(ProvidedStreamChannel.INSTANCE).build();

        assertThatThrownBy(() -> tie.build().exportRecords(definition, List.<Map<String, Object>>of().iterator(),
                ExportContext.builder().workingDirectory(work).build()))
                .isInstanceOfSatisfying(ConfigurationException.class, e -> assertThat(e.getProblems()).containsExactly(
                        "variable 'tenant' of outputNamePattern is not in the context",
                        "a ProvidedStreamChannel output requires providedOutput in the context"));
    }

    @Test
    void exportThenImportGivesTheSameValues() throws IOException {
        for (FileFormat format : FileFormat.values()) {
            Layout.Builder layout = Layout.builder("out").allowedFormats(format).xml(XmlOptions.of("rec"))
                    .fileNamePattern(null)
                    .field(FieldDefinition.builder("id").type(FieldType.INTEGER))
                    .field(FieldDefinition.builder("name"))
                    .field(FieldDefinition.builder("amount").type(FieldType.DECIMAL))
                    .field(FieldDefinition.builder("born").type(FieldType.DATE))
                    .field(FieldDefinition.builder("active").type(FieldType.BOOLEAN));
            Map<String, Object> record = record("id", 7L, "name", "José, \"Pepe\"", "amount", new BigDecimal("12.5"),
                    "born", LocalDate.of(2000, 2, 29), "active", true);
            Path directory = Files.createDirectories(root.resolve("round-" + format));
            ExportResult exported = tie.build().exportRecords(export(layout).outputChannel(LocalDirectoryChannel.of(directory))
                    .build(), List.of(record).iterator(), ExportContext.builder().workingDirectory(work).build());
            List<Map<String, Object>> imported = new ArrayList<>();

            tie.build().importFiles(IntegrationDefinition.importing("back").inputChannel(LocalDirectoryChannel.of(directory))
                            .layout(layout.fileNamePattern("out_{timestamp:yyyyMMddHHmmss}")).build(),
                    ImportContext.builder(r -> {
                        Map<String, Object> values = new HashMap<>();
                        for (String field : List.of("id", "name", "amount", "born", "active")) {
                            values.put(field, r.get(field));
                        }
                        imported.add(values);
                        return Outcome.APPLIED;
                    }).workingDirectory(work).build());

            assertThat(exported.status()).as(format.name()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(imported).as(format.name()).containsExactly(record);
        }
    }
}
