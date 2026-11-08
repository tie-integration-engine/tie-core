package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tie.internal.run.ImportHarness.csv;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.ErrorCode;
import org.tie.ExportContext;
import org.tie.FileErrorType;
import org.tie.FileResult;
import org.tie.FileStatus;
import org.tie.ImportResult;
import org.tie.ResultStatus;
import org.tie.Tie;
import org.tie.TypedRecord;
import org.tie.WarningCode;
import org.tie.config.DeliveryStrategy;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.PostSuccessAction;
import org.tie.config.TypeFormat;
import org.tie.config.XmlOptions;
import org.tie.internal.validation.DefinitionValidator;
import org.tie.spi.FileHook;

/** The rows of spec §8 that no other test covers. */
class EdgeCasesTest {

    @TempDir
    Path root;

    private ImportHarness h;

    @BeforeEach
    void setUp() {
        h = new ImportHarness(root);
    }

    @Test
    @DisplayName("§8 timestamps of names have no zone: the system or clock zone never shifts them (DST)")
    void timestampsIgnoreZones() {
        h.writeLines("alta_20261025023000.csv", 1);
        h.tie = Tie.builder().clock(Clock.fixed(Instant.parse("2026-10-25T01:30:00Z"), ZoneId.of("Europe/Madrid")));

        ImportResult result = h.run(h.altas().duplicateProtection(new DuplicateProtection(true, null)));

        assertThat(result.files().getFirst().timestamp()).isEqualTo(LocalDateTime.of(2026, 10, 25, 2, 30));
        assertThat(result.watermarks()).containsEntry("alta", LocalDateTime.of(2026, 10, 25, 2, 30));
    }

    @Test
    @DisplayName("§8 a caller that does not store the returned watermark reprocesses the file")
    void watermarkNotStored() {
        h.writeLines("alta_20261026120000.csv", 1);
        IntegrationDefinition.Builder definition = h.altas().duplicateProtection(new DuplicateProtection(true, null));

        ImportResult first = h.run(definition);
        ImportResult second = h.run(definition);
        h.context.watermarks(first.watermarks());
        ImportResult third = h.run(definition);

        assertThat(first.files()).hasSize(1);
        assertThat(second.files()).hasSize(1);
        assertThat(third.files()).isEmpty();
    }

    @Test
    @DisplayName("§8 a parent with thousands of children: each child is delivered right after its parent")
    void thousandsOfChildren() {
        StringBuilder json = new StringBuilder("[{\"id\":\"1\",\"children\":[");
        for (int i = 0; i < 5000; i++) {
            json.append(i > 0 ? "," : "").append("{\"sku\":\"s").append(i).append("\"}");
        }
        h.write("orders.json", json.append("]},{\"id\":\"2\"}]").toString());

        h.run(h.importOf(Layout.builder("order").fileNamePattern("orders").allowedFormats(FileFormat.JSON)
                        .field(FieldDefinition.builder("id")),
                Layout.builder("line").parent("order", "children").field(FieldDefinition.builder("sku"))));

        assertThat(h.records).hasSize(5002);
        assertThat(h.records.get(1).get("sku")).isEqualTo("s0");
        assertThat(h.records.get(5000).get("sku")).isEqualTo("s4999");
        assertThat(h.records.getLast()).extracting(TypedRecord::layout, TypedRecord::lineNumber)
                .containsExactly("order", 5002);
    }

    @Test
    @DisplayName("§8 non-ASCII file names are compared as they are")
    void nonAsciiNames() {
        h.writeLines("señal_ñandú_20261026.csv", 1);
        h.writeLines("senal_nandu_20261026.csv", 1);

        ImportResult result = h.run(h.importOf(csv("señal", "señal_ñandú_{timestamp:yyyyMMdd}")));

        assertThat(result.files()).extracting(FileResult::name).containsExactly("señal_ñandú_20261026.csv");
        assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.SUCCESS);
    }

    @Test
    @DisplayName("§8 the same file visible through two channels of a layout fails before processing (AC-17)")
    void sameFileInTwoChannels() throws IOException {
        Path mirror = Files.createDirectories(root.resolve("mirror"));
        h.writeLines("alta_20261026120000.csv", 1);
        Files.copy(h.in.resolve("alta_20261026120000.csv"), mirror.resolve("alta_20261026120000.csv"));

        ImportResult result = h.run(h.altas().inputChannel(LocalDirectoryChannel.of(mirror)));

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.error().type()).isEqualTo(FileErrorType.DUPLICATE_TIMESTAMP);
        assertThat(h.records).isEmpty();
    }

    @Test
    @DisplayName("§8 onFileCompleted is called once even if the MOVE after it fails (A9)")
    void completedOnceThenMoveFails() {
        AtomicInteger completed = new AtomicInteger();
        h.context.fileHook(new FileHook() {
            @Override
            public void onFileCompleted(FileResult partial) {
                completed.incrementAndGet();
            }
        });
        h.writeLines("alta_20261026120000.csv", 2);

        ImportResult result = h.run(h.altas().duplicateProtection(new DuplicateProtection(true, PostSuccessAction.move("nope"))));

        assertThat(completed).hasValue(1);
        assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.SUCCESS);
        assertThat(result.files().getFirst().warnings()).extracting(w -> w.code()).containsExactly(WarningCode.POST_ACTION_FAILED);
    }

    @Test
    @DisplayName("§8 a non-deterministic SPI can reject in the delivery pass a line the validation pass accepted")
    void nonDeterministicSpi() {
        AtomicInteger calls = new AtomicInteger();
        h.tie.transformer("flaky", (value, record, direction) -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("changed its mind");
            }
            return value;
        });
        h.writeLines("alta_20261026120000.csv", 1);

        FileResult file = h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                        .fields(List.of(FieldDefinition.builder("value").transformer("flaky").build())))
                .deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER)).files().getFirst();

        assertThat(file.status()).isEqualTo(FileStatus.SUCCESS);
        assertThat(file.rejected()).isEqualTo(1);
        assertThat(h.rejections.getFirst().errors().getFirst().code()).isEqualTo(ErrorCode.TRANSFORMER_FAILED);
    }

    @Test
    @DisplayName("§8 an XLSX text cell \"1.234,5\" in a DECIMAL field uses typeFormat as in CSV")
    void xlsxTextDecimal() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(h.in.resolve("importes.xlsx"))) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("amount");
            sheet.createRow(1).createCell(0).setCellValue("1.234,5");
            workbook.write(out);
        }

        h.run(h.importOf(Layout.builder("importes").fileNamePattern("importes").allowedFormats(FileFormat.XLSX)
                .field(FieldDefinition.builder("amount").type(FieldType.DECIMAL).typeFormat(TypeFormat.decimal(',', '.')))));

        assertThat(h.records).singleElement().satisfies(r -> assertThat(r.get("amount")).isEqualTo(new java.math.BigDecimal("1234.5")));
    }

    @Test
    @DisplayName("§8 VALIDATE_THEN_DELIVER in an export is a configuration error")
    void validateThenDeliverInExport() {
        IntegrationDefinition export = IntegrationDefinition.exporting("e").outputChannel(LocalDirectoryChannel.of(root))
                .outputNamePattern("out").deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER)
                .layout(Layout.builder("o").allowedFormats(FileFormat.CSV)).build();

        assertThat(DefinitionValidator.validate(export))
                .containsExactly("deliveryStrategy VALIDATE_THEN_DELIVER does not apply to exports");
    }

    @Test
    @DisplayName("§8 an exported XML child is named by its recordElement, or else by its layout")
    void exportedXmlChildNames() throws IOException {
        Path out = Files.createDirectories(root.resolve("out"));
        IntegrationDefinition export = IntegrationDefinition.exporting("e").outputChannel(LocalDirectoryChannel.of(out))
                .outputNamePattern("out")
                .layout(Layout.builder("order").allowedFormats(FileFormat.XML).xml(XmlOptions.of("order"))
                        .field(FieldDefinition.builder("id")))
                .layout(Layout.builder("line").parent("order", "lines").field(FieldDefinition.builder("sku")))
                .layout(Layout.builder("note").parent("order", "notes").xml(XmlOptions.of("n")).field(FieldDefinition.builder("t")))
                .build();
        Map<String, Object> order = Map.of("id", "1", "lines", List.of(Map.of("sku", "A")), "notes", List.of(Map.of("t", "x")));

        Tie.builder().build().exportRecords(export, List.of(order).iterator(), ExportContext.builder().workingDirectory(h.work).build());

        assertThat(Files.readString(out.resolve("out.xml"))).contains(
                "<order><id>1</id><lines><line><sku>A</sku></line></lines><notes><n><t>x</t></n></notes></order>");
    }
}
