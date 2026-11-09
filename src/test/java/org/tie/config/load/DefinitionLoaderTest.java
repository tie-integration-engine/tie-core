package org.tie.config.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tie.ConfigurationException;
import org.tie.config.ChannelDefinition;
import org.tie.config.ColumnLocation;
import org.tie.config.CopyMode;
import org.tie.config.DefaultMode;
import org.tie.config.CsvOptions;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.OnEmpty;
import org.tie.config.OnExistingFile;
import org.tie.config.PostSuccessAction;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.SftpChannel;
import org.tie.config.TextTransform;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.TypeFormat;
import org.tie.config.XlsxCellValues;
import org.tie.config.XlsxOptions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

class DefinitionLoaderTest {

    static String resource(String name) {
        try (InputStream in = DefinitionLoaderTest.class.getResourceAsStream("/definitions/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Minimal valid import document with {@code extra} appended to the first field. */
    private static String importYaml(String fieldExtra) {
        return """
                version: 1
                id: altas
                direction: IMPORT
                inputChannels: [{ type: LOCAL_DIRECTORY, directory: /in }]
                layouts:
                  - name: alta
                    fileNamePattern: alta_{timestamp:yyyyMMdd}
                    allowedFormats: [CSV]
                    fields:
                      - name: nif
                """ + fieldExtra.indent(8);
    }

    private static List<String> problemsOfYaml(String yaml) {
        try {
            DefinitionLoader.fromYaml(yaml);
        } catch (ConfigurationException e) {
            return e.getProblems();
        }
        throw new AssertionError("expected a ConfigurationException");
    }

    /** The §5.5 example built with the Java API. */
    static IntegrationDefinition specExampleWithBuilders() {
        return IntegrationDefinition.importing("altas")
                .inputChannel(SftpChannel.builder()
                        .host("sftp.example.com")
                        .username("tie")
                        .password("${secret:sftp}")
                        .remoteDirectory("/in")
                        .knownHostsFile(Path.of("/etc/tie/known_hosts"))
                        .timeout(Duration.ofSeconds(30))
                        .build())
                .duplicateProtection(new DuplicateProtection(true, PostSuccessAction.move("done/")))
                .layout(Layout.builder("alta")
                        .fileNamePattern("alta_{tenant}_{timestamp:yyyyMMddHHmmss}")
                        .allowedFormats(FileFormat.CSV, FileFormat.XLSX)
                        .csv(CsvOptions.builder().separator(';').build())
                        .xlsx(XlsxOptions.builder().sheet("Altas").build())
                        .columnLocation(ColumnLocation.HEADER)
                        .field(FieldDefinition.builder("nif")
                                .textTransform(TextTransform.UPPER)
                                .textTransform(TextTransform.padLeft(9, '0'))))
                .build();
    }

    @Nested
    class AcceptanceCriteria {

        @Test
        @DisplayName("AC-06 an unknown property is a configuration error that names it")
        void unknownProperty() {
            assertThat(problemsOfYaml(importYaml("colum: A")))
                    .containsExactly("layouts[0].fields[0]: unknown property 'colum'");
        }

        @Test
        @DisplayName("AC-07 equivalent JSON and YAML documents produce equal definitions")
        void jsonAndYamlAreEquivalent() {
            assertThat(DefinitionLoader.fromJson(resource("spec-example.json")))
                    .isEqualTo(DefinitionLoader.fromYaml(resource("spec-example.yaml")));
        }

        @Test
        @DisplayName("AC-195 MOVE and PAD_LEFT written as typed objects load equal to the builders")
        void typedObjectsMatchBuilders() {
            assertThat(DefinitionLoader.fromYaml(resource("spec-example.yaml"))).isEqualTo(specExampleWithBuilders());
        }

        @Test
        @DisplayName("AC-259 typeFormat {pattern, locale} loads a date with locale; the string form still works")
        void dateTypeFormatWithLocale() {
            String yaml = importYaml("type: DATE\ntypeFormat: { pattern: dd-MMM-yyyy, locale: es-ES }");

            assertThat(DefinitionLoader.fromYaml(yaml).layouts().getFirst().fields().getFirst().typeFormat())
                    .isEqualTo(TypeFormat.date("dd-MMM-yyyy", java.util.Locale.forLanguageTag("es-ES")));
            assertThat(DefinitionLoader.fromYaml(importYaml("type: DATE\ntypeFormat: dd/MM/yyyy")).layouts().getFirst()
                    .fields().getFirst().typeFormat()).isEqualTo(TypeFormat.date("dd/MM/yyyy"));
            assertThat(problemsOfYaml(yaml.replace("locale: es-ES", "locale: es_ES"))).containsExactly(
                    "layouts[0].fields[0].typeFormat.locale: 'es_ES' is not a valid BCP 47 language tag");
            assertThat(problemsOfYaml(yaml.replace("locale: es-ES", "decimalSeparator: \",\"")))
                    .singleElement().asString().startsWith("layouts[0].fields[0].typeFormat: ")
                    .contains("{pattern, locale}");
        }

        @Test
        @DisplayName("AC-252 the new XLSX options load; an ill-formed locale or an unknown cellValues is an error that names it")
        void xlsxOptionsAreLoaded() {
            String base = importYaml("") + "    formatOptions:\n      xlsx: { skipBlankRecords: true, cellValues: FORMATTED, locale: es-ES }\n";

            XlsxOptions xlsx = DefinitionLoader.fromYaml(base).layouts().getFirst().formatOptions().xlsx();
            assertThat(xlsx).isEqualTo(XlsxOptions.builder().skipBlankRecords(true).cellValues(XlsxCellValues.FORMATTED)
                    .locale(java.util.Locale.forLanguageTag("es-ES")).build());
            assertThat(problemsOfYaml(base.replace("locale: es-ES", "locale: es_ES"))).containsExactly(
                    "layouts[0].formatOptions.xlsx.locale: 'es_ES' is not a valid BCP 47 language tag");
            assertThat(problemsOfYaml(base.replace("cellValues: FORMATTED", "cellValues: RAW"))).containsExactly(
                    "layouts[0].formatOptions.xlsx.cellValues: expected one of CANONICAL, FORMATTED, got 'RAW'");
        }

        @Test
        @DisplayName("AC-260 defaultMode TEXT loads from YAML and JSON; an unknown mode is an error that names it")
        void defaultModeIsLoaded() {
            String yaml = importYaml("defaultValue: x\ndefaultMode: TEXT");
            String json = """
                    {"version": 1, "id": "altas", "direction": "IMPORT",
                     "inputChannels": [{"type": "LOCAL_DIRECTORY", "directory": "/in"}],
                     "layouts": [{"name": "alta", "fileNamePattern": "alta_{timestamp:yyyyMMdd}", "allowedFormats": ["CSV"],
                                  "fields": [{"name": "nif", "defaultValue": "x", "defaultMode": "TEXT"}]}]}
                    """;

            assertThat(DefinitionLoader.fromYaml(yaml).layouts().getFirst().fields().getFirst().defaultMode())
                    .isEqualTo(DefaultMode.TEXT);
            assertThat(DefinitionLoader.fromJson(json)).isEqualTo(DefinitionLoader.fromYaml(yaml));
            assertThat(problemsOfYaml(yaml.replace("defaultMode: TEXT", "defaultMode: RAW"))).containsExactly(
                    "layouts[0].fields[0].defaultMode: expected one of CONVERT, TEXT, got 'RAW'");
        }

        @Test
        @DisplayName("AC-227 copyMode CANONICAL loads from YAML and JSON; an unknown mode is an error that names it")
        void copyModeIsLoaded() {
            String yaml = importYaml("") + "      - name: code\n        copyFrom: nif\n        copyMode: CANONICAL\n";
            String json = """
                    {"version": 1, "id": "altas", "direction": "IMPORT",
                     "inputChannels": [{"type": "LOCAL_DIRECTORY", "directory": "/in"}],
                     "layouts": [{"name": "alta", "fileNamePattern": "alta_{timestamp:yyyyMMdd}", "allowedFormats": ["CSV"],
                                  "fields": [{"name": "nif"}, {"name": "code", "copyFrom": "nif", "copyMode": "CANONICAL"}]}]}
                    """;

            assertThat(DefinitionLoader.fromYaml(yaml).layouts().getFirst().fields().get(1).copyMode())
                    .isEqualTo(CopyMode.CANONICAL);
            assertThat(DefinitionLoader.fromJson(json)).isEqualTo(DefinitionLoader.fromYaml(yaml));
            assertThat(problemsOfYaml(yaml.replace("copyMode: CANONICAL", "copyMode: RAW"))).containsExactly(
                    "layouts[0].fields[1].copyMode: expected one of TEXT, CANONICAL, got 'RAW'");
        }

        @Test
        @DisplayName("AC-196 timeout is an ISO-8601 duration; a bare number is an error")
        void timeoutIsIsoDuration() {
            String yaml = resource("spec-example.yaml");
            SftpChannel sftp = (SftpChannel) DefinitionLoader.fromYaml(yaml).inputChannels().getFirst();

            assertThat(sftp.timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(problemsOfYaml(yaml.replace("timeout: PT30S", "timeout: 30")))
                    .containsExactly("inputChannels[0].timeout: expected an ISO-8601 duration such as PT30S, got 30");
        }
    }

    @Nested
    class Documents {

        @Test
        void everyOptionOfTheFullImportIsMapped() {
            IntegrationDefinition definition = DefinitionLoader.fromYaml(resource("full-import.yaml"));

            assertThat(definition.rejectionThreshold().maxRejectedPercent()).isEqualByComparingTo("12.5");
            assertThat(definition.duplicateProtection().postSuccessAction()).isEqualTo(PostSuccessAction.DELETE);
            assertThat(definition.resultReport().channel()).isInstanceOfSatisfying(
                    org.tie.config.LocalDirectoryChannel.class,
                    channel -> assertThat(channel.onExistingFile()).isEqualTo(OnExistingFile.FAIL));
            List<ChannelDefinition> inputs = definition.inputChannels();
            assertThat(inputs.get(0)).isInstanceOfSatisfying(SftpChannel.class, sftp -> {
                assertThat(sftp.port()).isEqualTo(2222);
                assertThat(sftp.privateKeyFile()).isEqualTo(Path.of("/etc/tie/id_ed25519"));
                assertThat(sftp.privateKeyPassphrase()).isEqualTo("${secret:passphrase}");
                assertThat(sftp.timeout()).isEqualTo(Duration.ofSeconds(90));
                assertThat(sftp.maxFileSize()).isEqualTo(10_485_760L);
            });
            assertThat(inputs.get(2)).isEqualTo(ProvidedStreamChannel.INSTANCE);

            Layout people = definition.layout("people").orElseThrow();
            assertThat(people.formatOptions().csv())
                    .isEqualTo(new CsvOptions(';', '\'', java.nio.charset.Charset.forName("ISO-8859-1"), true, 4, true));
            assertThat(people.formatOptions().xlsx()).isEqualTo(new XlsxOptions("People", false, 3, true, XlsxCellValues.FORMATTED,
                    java.util.Locale.forLanguageTag("es-ES")));
            FieldDefinition nif = people.fields().get(0);
            assertThat(nif.textTransforms()).containsExactly(TextTransform.UPPER, TextTransform.STRIP_ACCENTS,
                    TextTransform.padLeft(9, '0'), TextTransform.LOWER);
            assertThat(people.fields().get(1).translations()).containsExactly(
                    Translation.of("M", "Male"), Translation.of("F", "Female"),
                    new Translation("X", null, TranslationDirection.IN),
                    new Translation("Other", "O", TranslationDirection.OUT));
            assertThat(people.fields().get(2).typeFormat()).isEqualTo(TypeFormat.decimal(',', '.'));
            assertThat(people.fields().get(3).typeFormat()).isEqualTo(TypeFormat.date("dd/MM/yyyy"));
            assertThat(people.fields().get(4).typeFormat()).isEqualTo(TypeFormat.booleanLiterals("S", "N"));
            assertThat(people.fields().get(5).onEmpty()).isEqualTo(OnEmpty.DEFAULT);
            assertThat(people.fields().get(5).inInput()).isFalse();
            assertThat(people.fields().get(6).copyMode()).isEqualTo(CopyMode.CANONICAL);
            assertThat(people.fields().get(5).defaultMode()).isEqualTo(DefaultMode.TEXT);

            Layout line = definition.layout("line").orElseThrow();
            assertThat(line.parent()).isEqualTo("order");
            assertThat(line.nestedKey()).isEqualTo("lines");
            assertThat(definition.layout("order").orElseThrow().formatOptions().xml().encoding())
                    .isEqualTo(StandardCharsets.UTF_16);
        }

        @ParameterizedTest
        @ValueSource(strings = {"full-import.yaml", "export.yaml", "spec-example.yaml"})
        void yamlAndItsJsonConversionLoadEqual(String name) {
            String yaml = resource(name);
            String json = JsonMapper.builder().build().writeValueAsString(YAMLMapper.builder().build().readTree(yaml));

            assertThat(DefinitionLoader.fromJson(json)).isEqualTo(DefinitionLoader.fromYaml(yaml));
        }

        @Test
        void schemaIsPublishedOnTheClasspath() throws IOException {
            try (InputStream schema = DefinitionLoader.schema()) {
                assertThat(new String(schema.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("\"$schema\": \"https://json-schema.org/draft/2020-12/schema\"");
            }
        }
    }

    @Nested
    class Problems {

        @Test
        void allMappingProblemsAreReportedTogether() {
            String yaml = """
                    version: 1
                    id: 7
                    direction: SIDEWAYS
                    inputChannels:
                      - { type: SFTP, host: h, port: "22", username: u, password: p, remoteDirectory: /in,
                          knownHostsFile: /k, colour: blue }
                      - { type: FTP }
                    layouts:
                      - name: alta
                        fileNamePattern: alta_{timestamp:yyyyMMdd}
                        allowedFormats: [CSV, CSV, TXT]
                        formatOptions: { csv: { separator: ";;", encoding: KLINGON } }
                        fields:
                          - name: nif
                            textTransforms: [SHOUT, PAD_LEFT, { type: PAD_RIGHT, length: 3 }]
                            typeFormat: { decimalSeparator: ",", trueLiteral: S }
                    """;

            assertThat(problemsOfYaml(yaml)).containsExactly(
                    "id: expected a string, got 7",
                    "direction: expected one of IMPORT, EXPORT, got 'SIDEWAYS'",
                    "layouts[0].allowedFormats[2]: expected one of CSV, XLSX, XML, JSON, got 'TXT'",
                    "layouts[0].allowedFormats: must not contain duplicates",
                    "layouts[0].formatOptions.csv.separator: expected a single character, got ';;'",
                    "layouts[0].formatOptions.csv.encoding: unsupported encoding 'KLINGON'",
                    "layouts[0].fields[0].textTransforms[0]: expected one of UPPER, LOWER, STRIP_ACCENTS, PAD_LEFT,"
                            + " PAD_RIGHT, got 'SHOUT'",
                    "layouts[0].fields[0].textTransforms[1]: expected an object with length and char for PAD_LEFT,"
                            + " got 'PAD_LEFT'",
                    "layouts[0].fields[0].textTransforms[2].char: is required",
                    "layouts[0].fields[0].typeFormat: expected a date pattern, {pattern, locale},"
                            + " {decimalSeparator, groupingSeparator} or {trueLiteral, falseLiteral}, got an object",
                    "inputChannels[0].port: expected an integer, got '22'",
                    "inputChannels[0]: unknown property 'colour'",
                    "inputChannels[1].type: expected one of SFTP, LOCAL_DIRECTORY, PROVIDED_STREAM, got 'FTP'");
        }

        @Test
        void versionIsRequiredAndMustBeOne() {
            String yaml = importYaml("");

            assertThat(problemsOfYaml(yaml.replace("version: 1\n", ""))).containsExactly("version is required");
            assertThat(problemsOfYaml(yaml.replace("version: 1", "version: 2")))
                    .containsExactly("version: unsupported version 2 (expected 1)");
        }

        @Test
        void definitionIsValidatedAfterMapping() {
            assertThat(problemsOfYaml(importYaml("copyFrom: ghost")))
                    .containsExactly("layout 'alta', field 'nif': copyFrom 'ghost' does not exist");
        }

        @Test
        void nullIsOnlyAllowedForTranslationTargets() {
            assertThat(problemsOfYaml(importYaml("column: null"))).containsExactly(
                    "layouts[0].fields[0].column: must not be null");
            assertThat(DefinitionLoader.fromYaml(importYaml("translations: [{ from: X, to: null }]"))
                    .layouts().getFirst().fields().getFirst().translations())
                    .containsExactly(Translation.of("X", null));
        }

        @Test
        void shorthandsOnlyForValuesWithoutParameters() {
            String withAction = importYaml("") + "duplicateProtection: { postSuccessAction: MOVE }\n";

            assertThat(problemsOfYaml(withAction)).containsExactly(
                    "duplicateProtection.postSuccessAction: expected an object {type: MOVE, remoteFolder} for MOVE,"
                            + " got 'MOVE'");
            assertThat(DefinitionLoader.fromYaml(importYaml("") + "duplicateProtection: { postSuccessAction: { type: NONE } }\n")
                    .duplicateProtection().postSuccessAction()).isEqualTo(PostSuccessAction.NONE);
        }

        @Test
        void unparseableDocuments() {
            assertThatThrownBy(() -> DefinitionLoader.fromYaml("version: 1\nid: [unclosed\n"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageStartingWith("Invalid configuration: could not parse YAML document:")
                    .hasMessageContaining("line ");
            assertThatThrownBy(() -> DefinitionLoader.fromJson("{\"version\": 1, \"version\": 1}"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Duplicate Object property \"version\"");
            assertThatThrownBy(() -> DefinitionLoader.fromJson("{\"version\": 1} {}"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("could not parse JSON document");
            assertThat(problemsOfYaml("")).containsExactly("document is empty");
            assertThat(problemsOfYaml("- a\n- b\n")).containsExactly("document must be an object, got an array");
        }

        @Test
        void parseErrorsDoNotEchoTheDocument() {
            String yaml = "version: 1\npassword: hunter2-secret\nid: [unclosed\n";

            assertThatThrownBy(() -> DefinitionLoader.fromYaml(yaml))
                    .isInstanceOf(ConfigurationException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("hunter2-secret"));
        }

        @Test
        void numbersKeepTheirDecimalValue() {
            String yaml = importYaml("") + "deliveryStrategy: VALIDATE_THEN_DELIVER\n"
                    + "rejectionThreshold: { maxRejectedPercent: 0.1 }\n";

            assertThat(DefinitionLoader.fromYaml(yaml).rejectionThreshold().maxRejectedPercent())
                    .isEqualTo(new BigDecimal("0.1"));
        }
    }
}
