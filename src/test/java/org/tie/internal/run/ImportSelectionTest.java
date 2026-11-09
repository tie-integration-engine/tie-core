package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tie.internal.run.ImportHarness.csv;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.ConfigurationException;
import org.tie.FileErrorType;
import org.tie.FileResult;
import org.tie.FileStatus;
import org.tie.ImportResult;
import org.tie.ResultStatus;
import org.tie.WarningCode;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.ProvidedStreamChannel;

class ImportSelectionTest {

    @TempDir
    Path root;

    private ImportHarness h;

    @BeforeEach
    void setUp() {
        h = new ImportHarness(root);
    }

    private static List<String> names(ImportResult result) {
        return result.files().stream().map(FileResult::name).toList();
    }

    private static DuplicateProtection watermark() {
        return new DuplicateProtection(true, null);
    }

    @Test
    @DisplayName("AC-01 an invalid configuration throws with every problem and no channel is opened")
    void invalidConfiguration() {
        List<String> listed = new ArrayList<>();
        h.context.alreadyProcessed(file -> listed.add(file.name()));
        h.writeLines("alta_20261026120000.csv", 1);

        assertThatThrownBy(() -> h.run(h.importOf(
                csv("alta", "alta_{timestamp:yyyyMMddHHmmss}").field(FieldDefinition.builder("c").copyFrom("ghost")),
                csv(null, "baja_{timestamp:yyyyMMdd}"))))
                .isInstanceOfSatisfying(ConfigurationException.class, e -> assertThat(e.getProblems()).containsExactly(
                        "layout #2: name is required",
                        "layout 'alta', field 'c': copyFrom 'ghost' does not exist"));
        assertThat(listed).isEmpty();
        assertThat(h.events).isEmpty();
    }

    @Test
    @DisplayName("AC-03 importFiles rejects an unregistered transformer before any I/O")
    void unregisteredTransformer() {
        assertThatThrownBy(() -> h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                .fields(List.of(FieldDefinition.builder("value").transformer("x").build())))))
                .isInstanceOfSatisfying(ConfigurationException.class, e -> assertThat(e.getProblems())
                        .containsExactly("layout 'alta', field 'value': transformer 'x' is not registered"));
    }

    @Test
    @DisplayName("AC-149 importFiles requires every pattern variable in the context")
    void missingVariable() {
        assertThatThrownBy(() -> h.run(h.importOf(csv("alta", "alta_{tenant}_{timestamp:yyyyMMdd}"))))
                .isInstanceOfSatisfying(ConfigurationException.class, e -> assertThat(e.getProblems())
                        .containsExactly("layout 'alta': variable 'tenant' of fileNamePattern is not in the context"));
    }

    @Test
    @DisplayName("AC-12 a variable and a timestamp in the pattern")
    void variableAndTimestamp() {
        h.writeLines("alta_ACME_20261026120000.csv", 1);
        h.writeLines("alta_OTHER_20261026120000.csv", 1);
        h.context.variable("tenant", "ACME");

        ImportResult result = h.run(h.importOf(csv("alta", "alta_{tenant}_{timestamp:yyyyMMddHHmmss}")));

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.name()).isEqualTo("alta_ACME_20261026120000.csv");
            assertThat(file.timestamp()).isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0, 0));
        });
    }

    @Test
    @DisplayName("AC-13 AC-14 AC-15 only files strictly after the watermark are candidates")
    void watermarkIsStrict() {
        h.writeLines("alta_20261026115959.csv", 1);
        h.writeLines("alta_20261026120000.csv", 1);
        h.writeLines("alta_20261026120001.csv", 1);
        h.context.watermark("alta", LocalDateTime.of(2026, 10, 26, 12, 0, 0));

        ImportResult result = h.run(h.altas().duplicateProtection(watermark()));

        assertThat(names(result)).containsExactly("alta_20261026120001.csv");
        assertThat(result.watermarks()).containsEntry("alta", LocalDateTime.of(2026, 10, 26, 12, 0, 1));
    }

    @Test
    @DisplayName("AC-16 a matching file with an extension not allowed fails and stops the run")
    void extensionNotAllowed() {
        h.writeLines("alta_20261026120000.txt", 1);
        h.writeLines("alta_20261027120000.csv", 1);

        ImportResult result = h.run(h.altas());

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.FAILED, FileStatus.NOT_PROCESSED);
        assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.EXTENSION_NOT_ALLOWED);
        assertThat(result.files().getFirst().error().message()).startsWith("extension not allowed");
        assertThat(h.records).isEmpty();
    }

    @Test
    @DisplayName("AC-17 two files of a layout with the same timestamp fail the run before processing")
    void duplicateTimestamp() {
        h.writeLines("alta_20261026120000.csv", 1);
        h.write("alta_20261026120000.json", "[]");

        ImportResult result = h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                .allowedFormats(FileFormat.CSV, FileFormat.JSON)));

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.files()).isEmpty();
        assertThat(result.error().type()).isEqualTo(FileErrorType.DUPLICATE_TIMESTAMP);
        assertThat(result.error().message()).contains("alta_20261026120000.csv", "alta_20261026120000.json");
        assertThat(h.events).isEmpty();
    }

    @Test
    @DisplayName("AC-18 files are ordered by layout declaration, then by timestamp")
    void ordering() {
        h.writeLines("b_20260101.csv", 1);
        h.writeLines("a_20260303.csv", 1);
        h.writeLines("a_20260202.csv", 1);
        h.writeLines("b_20251231.csv", 1);

        ImportResult result = h.run(h.importOf(csv("A", "a_{timestamp:yyyyMMdd}"), csv("B", "b_{timestamp:yyyyMMdd}")));

        assertThat(names(result)).containsExactly("a_20260202.csv", "a_20260303.csv", "b_20251231.csv", "b_20260101.csv");
    }

    @Test
    @DisplayName("AC-19 a file the predicate reports as processed is not a candidate")
    void alreadyProcessed() {
        h.writeLines("alta_20261026120000.csv", 1);
        h.writeLines("alta_20261027120000.csv", 1);
        h.context.alreadyProcessed(file -> file.name().contains("1026")).watermark("alta", LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(names(h.run(h.altas().duplicateProtection(watermark())))).containsExactly("alta_20261027120000.csv");
    }

    @Test
    @DisplayName("AC-20 an unsafe file name is ignored with a warning")
    void unsafeName() {
        h.writeLines("alta..20261026120000.csv", 1);

        ImportResult result = h.run(h.importOf(csv("alta", "alta..{timestamp:yyyyMMddHHmmss}")));

        assertThat(result.files()).isEmpty();
        assertThat(result.warnings()).singleElement().satisfies(w -> assertThat(w.code()).isEqualTo(WarningCode.UNSAFE_FILE_NAME));
    }

    @Test
    @DisplayName("AC-21 files up to maxFileSize are processed; a larger one fails")
    void maxFileSize() {
        h.write("alta_20260101000000.csv", "value\n" + "x".repeat(999 - 6));
        h.write("alta_20260102000000.csv", "value\n" + "x".repeat(1000 - 6));
        h.write("alta_20260103000000.csv", "value\n" + "x".repeat(1001 - 6));

        ImportResult result = h.run(h.altas().inputChannels(List.of(new LocalDirectoryChannel(h.in, 1000L, null))));

        assertThat(result.files()).extracting(FileResult::status)
                .containsExactly(FileStatus.SUCCESS, FileStatus.SUCCESS, FileStatus.FAILED);
        assertThat(result.files().get(2).error().type()).isEqualTo(FileErrorType.FILE_TOO_LARGE);
        assertThat(result.files().get(2).error().message()).startsWith("file too large");
    }

    @Test
    @DisplayName("AC-22 no candidates is a success with unchanged watermarks")
    void noCandidates() {
        h.writeLines("other.csv", 1);
        h.context.watermark("alta", LocalDateTime.of(2026, 1, 1, 0, 0));

        ImportResult result = h.run(h.altas().duplicateProtection(watermark()));

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.files()).isEmpty();
        assertThat(result.watermarks()).containsExactly(Map.entry("alta", LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertThat(result.reports()).isEmpty();
    }

    @Test
    @DisplayName("AC-23 a date-only timestamp is that day at 00:00:00")
    void dateOnlyTimestamp() {
        h.writeLines("alta_20261026.csv", 1);

        assertThat(h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMdd}"))).files().getFirst().timestamp())
                .isEqualTo(LocalDateTime.of(2026, 10, 26, 0, 0, 0));
    }

    @Test
    @DisplayName("AC-147 the extension is matched ignoring case")
    void upperCaseExtension() {
        h.writeLines("alta_20261026.CSV", 2);

        ImportResult result = h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMdd}")));

        assertThat(result.files()).singleElement().satisfies(file -> assertThat(file.applied()).isEqualTo(2));
    }

    @Test
    @DisplayName("AC-148 the whole name must match: no wildcards")
    void wholeNameMustMatch() {
        h.writeLines("alta_20261026_x.csv", 1);

        assertThat(h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMdd}"))).files()).isEmpty();
    }

    @Test
    @DisplayName("AC-150 a name matching two layouts fails the run before processing")
    void ambiguousLayout() {
        h.writeLines("x_1.csv", 1);
        h.context.variable("n", "1").variable("m", "1");

        ImportResult result = h.run(h.importOf(csv("A", "x_{n}"), csv("B", "x_{m}")));

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.files()).isEmpty();
        assertThat(result.error().type()).isEqualTo(FileErrorType.AMBIGUOUS_LAYOUT);
        assertThat(result.error().message()).isEqualTo("file 'x_1.csv' matches layouts A, B");
    }

    @Test
    @DisplayName("AC-151 without timestamp, files of a layout differ only in extension and are ordered by name")
    void noTimestamp() {
        h.write("datos.json", "[{\"value\":\"j\"}]");
        h.writeLines("datos.csv", 1);

        ImportResult result = h.run(h.importOf(csv("datos", "datos").allowedFormats(FileFormat.CSV, FileFormat.JSON)));

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(names(result)).containsExactly("datos.csv", "datos.json");
        assertThat(result.files()).allSatisfy(file -> assertThat(file.timestamp()).isNull());
    }

    @Test
    @DisplayName("AC-153 when one channel fails to list, no file of any channel is processed")
    void secondChannelFails() {
        h.writeLines("alta_20261026120000.csv", 1);

        ImportResult result = h.run(h.altas().inputChannel(LocalDirectoryChannel.of(root.resolve("missing"))));

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.files()).isEmpty();
        assertThat(result.error().type()).isEqualTo(FileErrorType.CHANNEL);
        assertThat(h.records).isEmpty();
    }

    private static final class TrackedStream extends FilterInputStream {
        boolean closed;

        TrackedStream(String content) {
            super(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    @Test
    @DisplayName("AC-156 channel files first, then provided inputs in order; every stream is closed")
    void providedInputsOrderAndClosing() {
        h.writeLines("alta_20261026120000.csv", 1);
        TrackedStream p1 = new TrackedStream("value\n\"unclosed\n");
        TrackedStream p2 = new TrackedStream("value\nv\n");
        h.context.providedInput("alta", "p1.csv", p1).providedInput("alta", "p2.csv", p2);

        ImportResult result = h.run(h.altas().inputChannel(ProvidedStreamChannel.INSTANCE));

        assertThat(names(result)).containsExactly("alta_20261026120000.csv", "p1.csv", "p2.csv");
        assertThat(result.files()).extracting(FileResult::status)
                .containsExactly(FileStatus.SUCCESS, FileStatus.FAILED, FileStatus.NOT_PROCESSED);
        assertThat(p1.closed).isTrue();
        assertThat(p2.closed).isTrue();
    }

    @Test
    @DisplayName("AC-110 a provided input skips the pattern and never moves the watermark")
    void providedInputSkipsPatternAndWatermark() {
        LocalDateTime mark = LocalDateTime.of(2030, 1, 1, 0, 0);
        h.context.watermark("alta", mark).providedInput("alta", "foo.csv",
                new ByteArrayInputStream("value\nv\n".getBytes(StandardCharsets.UTF_8)));

        ImportResult result = h.run(h.altas().inputChannels(List.of(ProvidedStreamChannel.INSTANCE))
                .duplicateProtection(watermark()));

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(file.timestamp()).isNull();
        });
        assertThat(result.watermarks()).containsExactly(Map.entry("alta", mark));
    }

    @Test
    @DisplayName("AC-111 a provided input with an extension not allowed fails")
    void providedInputExtension() {
        h.context.providedInput("alta", "foo.txt", new ByteArrayInputStream(new byte[0]));

        ImportResult result = h.run(h.altas().inputChannels(List.of(ProvidedStreamChannel.INSTANCE)));

        assertThat(result.files().getFirst().error().message()).startsWith("extension not allowed");
    }

    @Test
    void providedInputsAreValidated() {
        TrackedStream stream = new TrackedStream("");
        h.context.providedInput("ghost", "../x.csv", stream);

        assertThatThrownBy(() -> h.run(h.altas()))
                .isInstanceOfSatisfying(ConfigurationException.class, e -> assertThat(e.getProblems()).containsExactly(
                        "providedInputs require a ProvidedStreamChannel input channel",
                        "providedInputs[0]: layout 'ghost' does not exist",
                        "providedInputs[0]: fileName '../x.csv' is not a safe file name"));
        assertThat(stream.closed).isTrue();
    }

    @Test
    void providedXlsxIsSpooledToTheWorkingDirectoryAndRemoved() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("value");
            sheet.createRow(1).createCell(0).setCellValue("x");
            workbook.write(bytes);
        }
        h.context.providedInput("alta", "p.xlsx", new ByteArrayInputStream(bytes.toByteArray()));

        ImportResult result = h.run(h.importOf(csv("alta", "alta").allowedFormats(FileFormat.XLSX))
                .inputChannels(List.of(ProvidedStreamChannel.INSTANCE)));

        assertThat(result.files().getFirst().applied()).isEqualTo(1);
        assertThat(h.filesIn(h.work)).isEmpty();
    }

    @Test
    void contextIsValidated() {
        org.tie.ImportContext withoutConsumer = org.tie.ImportContext.builder(null)
                .workingDirectory(root.resolve("nope")).build();

        assertThatThrownBy(() -> h.tie.build().importFiles(h.altas().build(), withoutConsumer))
                .isInstanceOfSatisfying(ConfigurationException.class, e -> assertThat(e.getProblems()).containsExactly(
                        "consumer is required", "workingDirectory " + root.resolve("nope") + " does not exist"));
        assertThatThrownBy(() -> h.tie.build().importFiles(h.altas().build(), null))
                .isInstanceOf(ConfigurationException.class);
    }

    /** The usual alta layout without fileNamePattern, fed only by provided inputs. */
    private org.tie.config.IntegrationDefinition.Builder providedOnly(String pattern) {
        return h.importOf(csv("alta", pattern)).inputChannels(List.of(ProvidedStreamChannel.INSTANCE));
    }

    @Test
    @DisplayName("AC-274 a root layout without fileNamePattern is valid and imports when every input channel is provided")
    void providedOnlyWithoutPattern() {
        org.tie.config.IntegrationDefinition definition = providedOnly(null).build();
        h.context.providedInput("alta", "p.csv", new ByteArrayInputStream("value\nv1\nv2\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(org.tie.internal.validation.DefinitionValidator.validate(definition)).isEmpty();
        ImportResult result = h.tie.build().importFiles(definition, h.context.build());

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.SUCCESS);
        assertThat(h.records).hasSize(2);
    }

    @Test
    @DisplayName("AC-276 a declared pattern is still checked with only provided inputs: a missing variable fails")
    void providedOnlyPatternVariables() {
        h.context.providedInput("alta", "p.csv", new ByteArrayInputStream("value\nv1\n".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> h.run(providedOnly("alta_{tenant}")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("variable 'tenant' of fileNamePattern is not in the context");
    }

    @Test
    @DisplayName("AC-277 with only provided inputs, no pattern and watermark, the import works and no mark moves")
    void providedOnlyWithWatermark() {
        org.tie.config.IntegrationDefinition definition = providedOnly(null)
                .duplicateProtection(new DuplicateProtection(true, org.tie.config.PostSuccessAction.NONE)).build();
        h.context.providedInput("alta", "p.csv", new ByteArrayInputStream("value\nv1\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(org.tie.internal.validation.DefinitionValidator.validate(definition)).isEmpty();
        ImportResult result = h.tie.build().importFiles(definition, h.context.build());

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.watermarks()).isEmpty();
    }
}
