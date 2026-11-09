package org.tie.internal.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tie.fixtures.Definitions.csvLayout;
import static org.tie.fixtures.Definitions.validExport;
import static org.tie.fixtures.Definitions.validImport;
import static org.tie.fixtures.Definitions.validNestedImport;
import static org.tie.fixtures.Definitions.validSftp;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tie.config.ColumnLocation;
import org.tie.config.CopyMode;
import org.tie.config.CsvOptions;
import org.tie.config.DefaultMode;
import org.tie.config.DeliveryStrategy;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnEmpty;
import org.tie.config.OutputAs;
import org.tie.config.PostSuccessAction;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.RejectionThreshold;
import org.tie.config.ReportMode;
import org.tie.config.ResultReport;
import org.tie.config.TextTransform;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.TypeFormat;
import org.tie.config.Validations;
import org.tie.config.XlsxOptions;
import org.tie.config.XmlOptions;

class DefinitionValidatorTest {

    private static List<String> problems(IntegrationDefinition.Builder definition) {
        return DefinitionValidator.validate(definition.build());
    }

    private static IntegrationDefinition.Builder importWith(Layout.Builder layout) {
        return validImport().layouts(List.of(layout.build()));
    }

    private static IntegrationDefinition.Builder importWithFields(FieldDefinition.Builder... fields) {
        return importWith(csvLayout("alta").fields(Stream.of(fields).map(FieldDefinition.Builder::build).toList()));
    }

    @Test
    void baselinesAreValid() {
        assertThat(problems(validImport())).isEmpty();
        assertThat(problems(validExport())).isEmpty();
        assertThat(problems(validNestedImport())).isEmpty();
        assertThat(problems(validImport().inputChannels(List.of(validSftp().build())))).isEmpty();
    }

    @Test
    void nullDefinitionIsAProblem() {
        assertThat(DefinitionValidator.validate(null)).containsExactly("definition is required");
    }

    @Nested
    class AcceptanceCriteria {

        @Test
        @DisplayName("AC-01 every problem is reported at once: nameless layout and unknown copyFrom")
        void reportsAllProblems() {
            IntegrationDefinition.Builder definition = validImport().layouts(List.of(
                    csvLayout("alta").field(FieldDefinition.builder("copy").copyFrom("ghost")).build(),
                    csvLayout("baja").name(null).build()));

            assertThat(problems(definition)).containsExactly(
                    "layout #2: name is required",
                    "layout 'alta', field 'copy': copyFrom 'ghost' does not exist");
        }

        @Test
        @DisplayName("AC-02 a copy cycle A -> B -> A is one problem naming the cycle")
        void copyCycle() {
            List<String> problems = problems(importWithFields(
                    FieldDefinition.builder("A").copyFrom("B"),
                    FieldDefinition.builder("B").copyFrom("A")));

            assertThat(problems).containsExactly("layout 'alta': copy cycle A -> B -> A");
        }

        @Test
        @DisplayName("AC-05 a percentage threshold requires VALIDATE_THEN_DELIVER")
        void percentageNeedsValidateThenDeliver() {
            IntegrationDefinition.Builder definition = validImport()
                    .rejectionThreshold(RejectionThreshold.maxRejectedPercent(BigDecimal.TEN));

            assertThat(problems(definition))
                    .containsExactly("rejectionThreshold: percentage threshold requires VALIDATE_THEN_DELIVER");
            assertThat(problems(definition.deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER))).isEmpty();
        }

        @Test
        @DisplayName("AC-08 a watermark requires {timestamp:…} in every root pattern")
        void watermarkNeedsTimestamp() {
            IntegrationDefinition.Builder definition = importWith(csvLayout("alta").fileNamePattern("alta_{tenant}"))
                    .duplicateProtection(new DuplicateProtection(true, null));

            assertThat(problems(definition))
                    .containsExactly("layout 'alta': watermark requires a {timestamp:…} marker in fileNamePattern");
        }

        @Test
        @DisplayName("AC-09 field names are unique ignoring case")
        void duplicateFieldIgnoringCase() {
            assertThat(problems(importWithFields(FieldDefinition.builder("Nif"), FieldDefinition.builder("NIF"))))
                    .containsExactly("layout 'alta', field 'NIF': duplicate field name (field names are case-insensitive)");
        }

        @Test
        @DisplayName("AC-10 column letters go from A to ZZZ")
        void columnLetters() {
            Layout.Builder layout = csvLayout("alta").columnLocation(ColumnLocation.LETTER).fields(List.of(
                    FieldDefinition.builder("a").column("ZZZ").build(),
                    FieldDefinition.builder("b").column("AAAA").build()));

            assertThat(problems(importWith(layout)))
                    .containsExactly("layout 'alta', field 'b': column 'AAAA' is not a valid letter (A to ZZZ)");
        }

        @Test
        @DisplayName("AC-11 an SFTP channel needs host key verification")
        void sftpHostKey() {
            IntegrationDefinition.Builder definition = validImport()
                    .inputChannels(List.of(validSftp().knownHostsFile(null).build()));

            assertThat(problems(definition)).containsExactly("inputChannels[0] (SFTP): host key verification requires"
                    + " knownHostsFile or hostKeyFingerprint (or an explicit insecureAcceptAnyHostKey)");
            assertThat(problems(validImport().inputChannels(List.of(
                    validSftp().knownHostsFile(null).insecureAcceptAnyHostKey(true).build())))).isEmpty();
        }

        @Test
        @DisplayName("AC-109 nested layouts in a CSV export")
        void nestedCsvExport() {
            IntegrationDefinition.Builder definition = validExport()
                    .layout(Layout.builder("child").parent("out", "children").field(FieldDefinition.builder("x")));

            assertThat(problems(definition))
                    .containsExactly("layout 'out': nested layouts are only supported in JSON and XML exports");
        }

        @Test
        @DisplayName("AC-237 defaultMode requires defaultValue")
        void defaultModeWithoutDefaultValue() {
            Layout.Builder layout = csvLayout("alta").field(FieldDefinition.builder("fecha").defaultMode(DefaultMode.TEXT));

            assertThat(problems(importWith(layout))).containsExactly("layout 'alta', field 'fecha': defaultMode requires defaultValue");
        }

        @Test
        @DisplayName("AC-225 copyMode requires copyFrom")
        void copyModeWithoutCopyFrom() {
            Layout.Builder layout = csvLayout("alta").field(FieldDefinition.builder("fecha").copyMode(CopyMode.CANONICAL));

            assertThat(problems(importWith(layout))).containsExactly("layout 'alta', field 'fecha': copyMode requires copyFrom");
            assertThat(problems(importWith(csvLayout("alta").field(FieldDefinition.builder("copia").copyFrom("nif")
                    .copyMode(CopyMode.TEXT))))).isEmpty();
        }

        @Test
        @DisplayName("AC-145 firstDataRow must leave room for the header")
        void firstDataRowWithHeader() {
            Layout.Builder layout = csvLayout("alta").csv(CsvOptions.builder().firstDataRow(1).build());

            assertThat(problems(importWith(layout)))
                    .containsExactly("layout 'alta': csv: firstDataRow must be at least 2 when hasHeader is true");
        }

        @Test
        @DisplayName("AC-178 outputAs NATIVE cannot be combined with typeFormat")
        void nativeWithTypeFormat() {
            IntegrationDefinition.Builder definition = validExport().layouts(List.of(Layout.builder("out")
                    .allowedFormats(FileFormat.JSON)
                    .field(FieldDefinition.builder("fecha").type(FieldType.DATE)
                            .typeFormat(TypeFormat.date("dd/MM/yyyy")).outputAs(OutputAs.NATIVE))
                    .build()));

            assertThat(problems(definition)).containsExactly("layout 'out', field 'fecha': outputAs NATIVE cannot be"
                    + " combined with typeFormat, textTransforms, translations or transformer");
        }

        @Test
        @DisplayName("AC-192 SFTP needs exactly one of password, privateKey or privateKeyFile")
        void sftpCredentials() {
            String expected = "inputChannels[0] (SFTP): exactly one of password, privateKey or privateKeyFile is required";

            assertThat(problems(validImport().inputChannels(List.of(validSftp().privateKey("PEM").build()))))
                    .containsExactly(expected);
            assertThat(problems(validImport().inputChannels(List.of(validSftp().password(null).build()))))
                    .containsExactly(expected);
        }
    }

    @Nested
    class CopyCycles {

        static Stream<Arguments> cycles() {
            return Stream.of(
                    Arguments.of(List.of(FieldDefinition.builder("A").copyFrom("A")), "A -> A"),
                    Arguments.of(List.of(FieldDefinition.builder("A").copyFrom("B"),
                            FieldDefinition.builder("B").copyFrom("A")), "A -> B -> A"),
                    Arguments.of(List.of(FieldDefinition.builder("A").copyFrom("B"),
                            FieldDefinition.builder("B").copyFrom("C"),
                            FieldDefinition.builder("C").copyFrom("A")), "A -> B -> C -> A"),
                    Arguments.of(List.of(FieldDefinition.builder("X").copyFrom("A"),
                            FieldDefinition.builder("A").copyFrom("B"),
                            FieldDefinition.builder("B").copyFrom("A")), "A -> B -> A"));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("cycles")
        void reportsEachCycleOnce(List<FieldDefinition.Builder> fields, String cycle) {
            assertThat(problems(importWithFields(fields.toArray(FieldDefinition.Builder[]::new))))
                    .containsExactly("layout 'alta': copy cycle " + cycle);
        }

        @Test
        void chainsWithoutCyclesAreValidWhateverTheDeclarationOrder() {
            assertThat(problems(importWithFields(
                    FieldDefinition.builder("C").copyFrom("B"),
                    FieldDefinition.builder("B").copyFrom("a"),
                    FieldDefinition.builder("A")))).isEmpty();
        }
    }

    @Nested
    class Layouts {

        @Test
        void requiresLayoutsAndUniqueNames() {
            assertThat(problems(validImport().layouts(List.of()))).containsExactly("at least one layout is required");
            assertThat(problems(validImport().layout(csvLayout("alta"))))
                    .containsExactly("layout 'alta': duplicate layout name");
        }

        @Test
        void rootImportLayoutNeedsAValidPatternAndFormats() {
            assertThat(problems(importWith(csvLayout("alta").fileNamePattern(null))))
                    .containsExactly("layout 'alta': fileNamePattern is required");
            assertThat(problems(importWith(csvLayout("alta").fileNamePattern("alta_{x"))))
                    .singleElement().asString().startsWith("layout 'alta': fileNamePattern: pattern 'alta_{x' has an unclosed '{'");
            assertThat(problems(importWith(csvLayout("alta").allowedFormats(java.util.Set.of()))))
                    .containsExactly("layout 'alta': at least one allowed format is required");
        }

        @Test
        void headerLocationNeedsAHeaderUnlessThereAreNoFields() {
            Layout.Builder noHeader = csvLayout("alta").csv(CsvOptions.builder().hasHeader(false).firstDataRow(1).build());

            assertThat(problems(importWith(noHeader))).containsExactly("layout 'alta': HEADER column location requires"
                    + " hasHeader (set columnLocation to POSITION or LETTER)");
            assertThat(problems(importWith(noHeader.columnLocation(ColumnLocation.POSITION)))).isEmpty();
            assertThat(problems(importWith(noHeader.columnLocation(ColumnLocation.HEADER).fields(List.of())))).isEmpty();
        }

        @Test
        @DisplayName("AC-314 a masked credential (***) is a configuration problem")
        void maskedCredentials() {
            IntegrationDefinition.Builder password = validImport().inputChannels(List.of(validSftp().password("***").build()));
            IntegrationDefinition.Builder key = validImport().inputChannels(List.of(validSftp().password(null)
                    .privateKey("***").build()));

            assertThat(problems(password)).singleElement().asString()
                    .endsWith("password is masked (***): set the real value or a ${secret:name} reference");
            assertThat(problems(key)).singleElement().asString().contains("privateKey is masked (***)");
        }

        @Test
        @DisplayName("AC-275 fileNamePattern stays required when any input channel lists files")
        void patternRequiredWithListingChannels() {
            Layout.Builder noPattern = csvLayout("alta").fileNamePattern(null);

            assertThat(problems(importWith(noPattern))).containsExactly("layout 'alta': fileNamePattern is required");
            assertThat(problems(importWith(noPattern).inputChannels(List.of(LocalDirectoryChannel.of(java.nio.file.Path.of("/in")),
                    ProvidedStreamChannel.INSTANCE)))).containsExactly("layout 'alta': fileNamePattern is required");
            assertThat(problems(importWith(noPattern).inputChannels(List.of(ProvidedStreamChannel.INSTANCE)))).isEmpty();
        }

        @Test
        @DisplayName("AC-276 with only provided inputs a declared pattern is still validated")
        void declaredPatternValidatedWithProvidedInputs() {
            assertThat(problems(importWith(csvLayout("alta").fileNamePattern("alta_{timestamp:"))
                    .inputChannels(List.of(ProvidedStreamChannel.INSTANCE))))
                    .singleElement().asString().startsWith("layout 'alta': fileNamePattern");
        }

        @Test
        @DisplayName("AC-272 recordProperty and firstArray cannot be combined on import")
        void recordPropertyWithFirstArray() {
            Layout.Builder layout = csvLayout("alta").allowedFormats(FileFormat.JSON)
                    .json(JsonOptions.builder().recordProperty("x").firstArray(true).build());

            assertThat(problems(importWith(layout)))
                    .containsExactly("layout 'alta': json recordProperty and firstArray cannot be combined");
        }

        @Test
        @DisplayName("AC-265 recordElement is required to export XML, not to import it")
        void recordElementOnlyRequiredForExport() {
            Layout.Builder export = Layout.builder("out").allowedFormats(FileFormat.XML);

            assertThat(problems(validExport().layouts(List.of(export.build()))))
                    .contains("layout 'out': xml recordElement is required");
            assertThat(problems(importWith(csvLayout("alta").allowedFormats(FileFormat.XML)))).isEmpty();
            assertThat(problems(importWith(csvLayout("alta").allowedFormats(FileFormat.XML).xml(XmlOptions.of(" ")))))
                    .containsExactly("layout 'alta': xml recordElement must not be blank");
        }

        @Test
        void formatOptionsAreCheckedForAllowedFormats() {
            Layout.Builder layout = csvLayout("alta")
                    .allowedFormats(FileFormat.CSV, FileFormat.XLSX, FileFormat.XML)
                    .csv(CsvOptions.builder().separator('"').firstDataRow(0).build())
                    .xlsx(XlsxOptions.builder().sheet(" ").build());

            assertThat(problems(importWith(layout))).containsExactly(
                    "layout 'alta': csv: firstDataRow must be at least 1",
                    "layout 'alta': csv separator and quote must be different",
                    "layout 'alta': xlsx sheet must not be blank");
            assertThat(problems(importWith(csvLayout("alta").allowedFormats(FileFormat.XML)
                    .xml(XmlOptions.of("record"))))).isEmpty();
        }

        @Test
        void childLayouts() {
            IntegrationDefinition.Builder definition = validNestedImport()
                    .layout(Layout.builder("orphan").parent("ghost", "x"))
                    .layout(Layout.builder("noKey").parent("order"))
                    .layout(Layout.builder("dupKey").parent("order", "lines"))
                    .layout(Layout.builder("withPattern").parent("order", "notes").fileNamePattern("x"));

            assertThat(problems(definition)).containsExactly(
                    "layout 'orphan': parent 'ghost' does not exist",
                    "layout 'noKey': nestedKey is required for a child layout",
                    "layout 'withPattern': fileNamePattern does not apply to child layouts",
                    "layout 'dupKey': duplicate nestedKey 'lines' under parent 'order'");
        }

        @Test
        void parentCyclesAreReportedOnce() {
            IntegrationDefinition.Builder definition = validNestedImport()
                    .layout(Layout.builder("p").parent("q", "a"))
                    .layout(Layout.builder("q").parent("p", "b"));

            assertThat(problems(definition)).containsExactly("layout 'p': parent cycle p -> q -> p");
        }

        @Test
        void nestedImportOfTabularFormats() {
            IntegrationDefinition.Builder definition = validImport()
                    .layout(Layout.builder("child").parent("alta", "children"));

            assertThat(problems(definition))
                    .containsExactly("layout 'alta': nested layouts are only supported in JSON and XML");
        }
    }

    @Nested
    class Fields {

        @Test
        void letterLocationNeedsALetterForInputFields() {
            Layout.Builder layout = csvLayout("alta").columnLocation(ColumnLocation.LETTER).fields(List.of(
                    FieldDefinition.builder("a").build(),
                    FieldDefinition.builder("b").column("b").build(),
                    FieldDefinition.builder("c").inInput(false).defaultValue("1").build()));

            assertThat(problems(importWith(layout))).containsExactly(
                    "layout 'alta', field 'a': column letter is required with LETTER column location",
                    "layout 'alta', field 'b': column 'b' is not a valid letter (A to ZZZ)");
        }

        @Test
        void optionalOnlyWithHeader() {
            Layout.Builder layout = csvLayout("alta").columnLocation(ColumnLocation.POSITION)
                    .fields(List.of(FieldDefinition.builder("a").optional(true).build()));

            assertThat(problems(importWith(layout)))
                    .containsExactly("layout 'alta', field 'a': optional only applies with HEADER column location");
        }

        @Test
        void emptyHandlingAndDefaults() {
            assertThat(problems(importWithFields(FieldDefinition.builder("a").onEmpty(OnEmpty.DEFAULT))))
                    .containsExactly("layout 'alta', field 'a': onEmpty DEFAULT requires defaultValue");
            assertThat(problems(importWithFields(FieldDefinition.builder("a").onEmpty(OnEmpty.KEEP).defaultValue("x"))))
                    .containsExactly("layout 'alta', field 'a': defaultValue only applies with onEmpty DEFAULT");
            assertThat(problems(importWithFields(FieldDefinition.builder("a").defaultValue("x")))).isEmpty();
        }

        @Test
        void typeFormatMustMatchTheType() {
            assertThat(problems(importWithFields(
                    FieldDefinition.builder("a").typeFormat(TypeFormat.date("dd/MM/yyyy")),
                    FieldDefinition.builder("b").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy{")),
                    FieldDefinition.builder("c").type(FieldType.DECIMAL).typeFormat(TypeFormat.decimal(',', ',')),
                    FieldDefinition.builder("d").type(FieldType.BOOLEAN).typeFormat(TypeFormat.booleanLiterals("S", "S")),
                    FieldDefinition.builder("e").type(FieldType.INTEGER).typeFormat(TypeFormat.booleanLiterals("S", "N")))))
                    .containsExactly(
                            "layout 'alta', field 'a': a date format only applies to DATE and DATETIME",
                            "layout 'alta', field 'b': invalid date format 'dd/MM/yyyy{': Pattern includes reserved character: '{'",
                            "layout 'alta', field 'c': decimal and grouping separators must be different",
                            "layout 'alta', field 'd': boolean literals must be different",
                            "layout 'alta', field 'e': boolean literals only apply to BOOLEAN");
        }

        @Test
        void validations() {
            assertThat(problems(importWithFields(
                    FieldDefinition.builder("a").validations(new Validations(5, 2, null, null)),
                    FieldDefinition.builder("b").validations(Validations.regex("[0-9")),
                    FieldDefinition.builder("c").validations(Validations.allowedValues(List.of())),
                    FieldDefinition.builder("d").validations(new Validations(-1, 0, null, null)))))
                    .containsExactly(
                            "layout 'alta', field 'a': minLength must not be greater than maxLength",
                            "layout 'alta', field 'b': invalid regex '[0-9': Unclosed character class",
                            "layout 'alta', field 'c': allowedValues must not be empty",
                            "layout 'alta', field 'd': minLength must not be negative",
                            "layout 'alta', field 'd': maxLength must be at least 1");
        }

        @Test
        void translationsAndTextTransforms() {
            assertThat(problems(importWithFields(
                    FieldDefinition.builder("a").translation("M", "Male").translation("M", "Man"),
                    FieldDefinition.builder("b").translation("M", "X").translation("H", "X"),
                    FieldDefinition.builder("c").translation(new Translation("M", "X", TranslationDirection.IN))
                            .translation(new Translation("H", "X", TranslationDirection.IN)),
                    FieldDefinition.builder("d").textTransform(TextTransform.padLeft(0, '0')))))
                    .containsExactly(
                            "layout 'alta', field 'a': translation from 'M' is defined more than once for import",
                            "layout 'alta', field 'b': translation for 'X' is defined more than once for export",
                            "layout 'alta', field 'd': padding length must be at least 1");
        }
    }

    @Nested
    class DirectionSettings {

        @Test
        void importRequiresInputsAndRejectsExportSettings() {
            IntegrationDefinition.Builder definition = validImport().inputChannels(List.of())
                    .outputChannel(ProvidedStreamChannel.INSTANCE).outputNamePattern("x");

            assertThat(problems(definition)).containsExactly(
                    "at least one input channel is required",
                    "outputChannel does not apply to imports",
                    "outputNamePattern does not apply to imports");
        }

        @Test
        void exportRequiresOutputAndRejectsImportSettings() {
            IntegrationDefinition.Builder definition = validExport().outputChannel(null).outputNamePattern(null)
                    .inputChannel(ProvidedStreamChannel.INSTANCE)
                    .duplicateProtection(new DuplicateProtection(true, null))
                    .resultReport(new ResultReport(ReportMode.REJECTED, FileFormat.CSV, null, "r"));

            assertThat(problems(definition)).containsExactly(
                    "outputChannel is required for exports",
                    "outputNamePattern is required for exports",
                    "inputChannels do not apply to exports",
                    "duplicateProtection does not apply to exports",
                    "resultReport does not apply to exports");
        }

        @Test
        void exportHasOneRootLayoutWithOneFormat() {
            IntegrationDefinition.Builder definition = validExport().layouts(List.of(
                    Layout.builder("a").allowedFormats(FileFormat.CSV, FileFormat.JSON).build(),
                    Layout.builder("b").allowedFormats(FileFormat.CSV).fileNamePattern("b").build()));

            assertThat(problems(definition)).containsExactly(
                    "layout 'a': an export layout must allow exactly one format",
                    "layout 'b': fileNamePattern does not apply to exports (use outputNamePattern)",
                    "an export must have exactly one root layout");
        }

        @Test
        void thresholdRanges() {
            IntegrationDefinition.Builder definition = validImport()
                    .deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER)
                    .rejectionThreshold(new RejectionThreshold(-1, new BigDecimal("100.5")));

            assertThat(problems(definition)).containsExactly(
                    "rejectionThreshold: maxRejected must not be negative",
                    "rejectionThreshold: maxRejectedPercent must be between 0 and 100");
        }

        @Test
        void moveFolderAndResultReport() {
            IntegrationDefinition.Builder definition = validImport()
                    .duplicateProtection(new DuplicateProtection(false, PostSuccessAction.move("../done")))
                    .resultReport(new ResultReport(ReportMode.REJECTED, null, ProvidedStreamChannel.INSTANCE, "r_{"));

            assertThat(problems(definition)).containsExactly(
                    "duplicateProtection: MOVE remoteFolder must not contain '..' or '\\'",
                    "resultReport: format is required",
                    "resultReport: channel cannot be a ProvidedStreamChannel",
                    "resultReport: namePattern: pattern 'r_{' has an unclosed '{' at position 2");
        }
    }

    @Nested
    class Sftp {

        @Test
        void channelSettings() {
            IntegrationDefinition.Builder definition = validImport().inputChannels(List.of(validSftp()
                    .host(" ").port(0).username(null).remoteDirectory(null).timeout(Duration.ZERO).maxFileSize(0L)
                    .hostKeyFingerprint("SHA256:x").build()));

            assertThat(problems(definition)).containsExactly(
                    "inputChannels[0] (SFTP): host is required",
                    "inputChannels[0] (SFTP): port must be between 1 and 65535",
                    "inputChannels[0] (SFTP): username is required",
                    "inputChannels[0] (SFTP): remoteDirectory is required",
                    "inputChannels[0] (SFTP): only one of knownHostsFile or hostKeyFingerprint is allowed",
                    "inputChannels[0] (SFTP): timeout must be positive",
                    "inputChannels[0] (SFTP): maxFileSize must be at least 1");
        }

        @Test
        void secretsAndPassphrase() {
            IntegrationDefinition.Builder definition = validImport().inputChannels(List.of(validSftp()
                    .password("${secret:bad name}").privateKeyPassphrase("x").build()));

            assertThat(problems(definition)).containsExactly(
                    "inputChannels[0] (SFTP): privateKeyPassphrase requires privateKey or privateKeyFile",
                    "inputChannels[0] (SFTP): password has a malformed secret reference (expected ${secret:name})");
        }
    }
}
