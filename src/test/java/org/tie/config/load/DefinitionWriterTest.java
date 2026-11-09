package org.tie.config.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tie.ConfigurationException;
import org.tie.config.CsvOptions;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.PostSuccessAction;
import org.tie.config.RejectionThreshold;
import org.tie.config.ReportMode;
import org.tie.config.ResultReport;
import org.tie.config.SftpChannel;
import org.tie.config.TextTransform;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.TypeFormat;
import org.tie.config.Validations;
import org.tie.fixtures.Definitions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

class DefinitionWriterTest {

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = DefinitionLoader.schema()) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        }
    }

    private static void assertValidJson(String json) {
        assertThat(schema.validate(JsonMapper.builder().build().readTree(json))).as("schema errors").isEmpty();
    }

    private static void assertValidYaml(String yaml) {
        assertThat(schema.validate(YAMLMapper.builder().build().readTree(yaml))).as("schema errors").isEmpty();
    }

    private static void assertRoundTrip(IntegrationDefinition definition) {
        String json = DefinitionWriter.toJson(definition);
        String yaml = DefinitionWriter.toYaml(definition);
        assertValidJson(json);
        assertValidYaml(yaml);
        assertThat(DefinitionLoader.fromJson(json)).isEqualTo(definition);
        assertThat(DefinitionLoader.fromYaml(yaml)).isEqualTo(definition);
    }

    /** Every example definition of the tests that has no literal credential. */
    static Stream<Arguments> examples() {
        return Stream.of(
                Arguments.of("full-import.yaml", DefinitionLoader.fromYaml(DefinitionLoaderTest.resource("full-import.yaml"))),
                Arguments.of("export.yaml", DefinitionLoader.fromYaml(DefinitionLoaderTest.resource("export.yaml"))),
                Arguments.of("spec-example.yaml", DefinitionLoader.fromYaml(DefinitionLoaderTest.resource("spec-example.yaml"))),
                Arguments.of("spec-example.json", DefinitionLoader.fromJson(DefinitionLoaderTest.resource("spec-example.json"))),
                Arguments.of("spec example with builders", DefinitionLoaderTest.specExampleWithBuilders()),
                Arguments.of("validImport", Definitions.validImport().build()),
                Arguments.of("validExport", Definitions.validExport().build()),
                Arguments.of("validNestedImport", Definitions.validNestedImport().build()),
                Arguments.of("SFTP with a secret reference", Definitions.validImport()
                        .inputChannels(List.of(Definitions.validSftp().password("${secret:sftp}").build())).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    @DisplayName("AC-278 every example definition round-trips through JSON and YAML and passes the schema")
    void roundTrip(String name, IntegrationDefinition definition) {
        assertRoundTrip(definition);
    }

    @Test
    @DisplayName("AC-279 the §5.5 example written as YAML has no option with its default value")
    void defaultsAreLeftOut() {
        String yaml = DefinitionWriter.toYaml(DefinitionLoaderTest.specExampleWithBuilders());

        assertThat(yaml).doesNotContain("port:", "hasHeader:", "timeout:", "onUnmatched: ERROR", "direction: BOTH",
                "inInput:", "type: STRING", "encoding:", "rootElement:", "watermark: false");
    }

    @Test
    @DisplayName("AC-280 a minimal definition is written exactly, with indentation")
    void minimalDefinition() {
        IntegrationDefinition definition = IntegrationDefinition.importing("altas")
                .layout(Layout.builder("alta").fileNamePattern("alta_{timestamp:yyyyMMdd}").allowedFormats(FileFormat.CSV)
                        .field(FieldDefinition.builder("nif")))
                .inputChannel(LocalDirectoryChannel.of(Path.of("/in")))
                .build();

        assertThat(DefinitionWriter.toJson(definition)).isEqualTo("""
                {
                  "version" : 1,
                  "id" : "altas",
                  "direction" : "IMPORT",
                  "layouts" : [ {
                    "name" : "alta",
                    "fileNamePattern" : "alta_{timestamp:yyyyMMdd}",
                    "allowedFormats" : [ "CSV" ],
                    "fields" : [ {
                      "name" : "nif"
                    } ]
                  } ],
                  "inputChannels" : [ {
                    "type" : "LOCAL_DIRECTORY",
                    "directory" : "/in"
                  } ]
                }
                """);
    }

    private static IntegrationDefinition withSftp(SftpChannel sftp) {
        return Definitions.validImport().inputChannels(List.of(sftp)).build();
    }

    @Test
    @DisplayName("AC-281 a literal password is masked, a secret reference is kept, a malformed one does not validate")
    void passwordMasking() {
        assertThat(DefinitionWriter.toJson(withSftp(Definitions.validSftp().password("s3cret").build())))
                .contains("\"password\" : \"***\"").doesNotContain("s3cret");
        assertThat(DefinitionWriter.toJson(withSftp(Definitions.validSftp().password("${secret:sftp}").build())))
                .contains("\"password\" : \"${secret:sftp}\"");
        assertThatThrownBy(() -> DefinitionWriter.toJson(withSftp(Definitions.validSftp().password("${secret:}").build())))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> assertThat(((ConfigurationException) e).getProblems())
                        .anySatisfy(problem -> assertThat(problem).contains("malformed secret reference")));
    }

    @Test
    @DisplayName("AC-282 private key, passphrase and the report channel's password are masked")
    void otherCredentialsMasked() {
        SftpChannel keyChannel = Definitions.validSftp().password(null)
                .privateKey("-----BEGIN OPENSSH PRIVATE KEY-----KEYDATA").privateKeyPassphrase("pp-secret").build();
        SftpChannel reportChannel = Definitions.validSftp().password("rp4ss").build();
        IntegrationDefinition definition = Definitions.validImport().inputChannels(List.of(keyChannel))
                .resultReport(new ResultReport(ReportMode.REJECTED, FileFormat.CSV, reportChannel, "{sourceName}_result"))
                .build();

        String yaml = DefinitionWriter.toYaml(definition);

        assertThat(yaml).doesNotContain("KEYDATA", "pp-secret", "rp4ss");
        assertThat(yaml.split("\\*\\*\\*", -1)).hasSize(4);
    }

    @Test
    @DisplayName("AC-283 a document written with a literal password keeps everything else and does not load back")
    void maskedDocumentDoesNotLoad() {
        String json = DefinitionWriter.toJson(withSftp(Definitions.validSftp().password("s3cret").build()));

        assertThat(json).contains("\"password\" : \"***\"", "\"host\" : \"sftp.example.com\"");
        assertThatThrownBy(() -> DefinitionLoader.fromJson(json))
                .isInstanceOf(ConfigurationException.class)
                .satisfies(e -> assertThat(((ConfigurationException) e).getProblems())
                        .anySatisfy(problem -> assertThat(problem).contains("password is masked (***)")));
    }

    @Test
    @DisplayName("AC-284 each typeFormat has its fixed form")
    void typeFormatForms() {
        IntegrationDefinition definition = Definitions.validImport().layouts(List.of(Definitions.csvLayout("alta")
                .fields(List.of(
                        FieldDefinition.builder("a").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy")).build(),
                        FieldDefinition.builder("b").type(FieldType.DATE)
                                .typeFormat(TypeFormat.date("dd-MMM-yyyy", Locale.forLanguageTag("es-ES"))).build(),
                        FieldDefinition.builder("c").type(FieldType.DECIMAL).typeFormat(TypeFormat.decimal(',', '.')).build(),
                        FieldDefinition.builder("d").type(FieldType.BOOLEAN)
                                .typeFormat(TypeFormat.booleanLiterals("S", "N")).build()))
                .build())).build();

        String yaml = DefinitionWriter.toYaml(definition);

        assertThat(yaml).contains("typeFormat: dd/MM/yyyy", "pattern: dd-MMM-yyyy", "locale: es-ES",
                "decimalSeparator: \",\"", "groupingSeparator: \".\"", "trueLiteral: S", "falseLiteral: \"N\"");
        assertRoundTrip(definition);
    }

    @Test
    @DisplayName("AC-285 text transforms and post-success actions have their fixed forms")
    void transformAndActionForms() {
        Layout.Builder layout = Definitions.csvLayout("alta").fields(List.of(FieldDefinition.builder("nif")
                .textTransform(TextTransform.UPPER).textTransform(TextTransform.padLeft(9, '0')).build()));
        String none = DefinitionWriter.toYaml(Definitions.validImport().layouts(List.of(layout.build()))
                .duplicateProtection(new DuplicateProtection(true, PostSuccessAction.NONE)).build());
        String delete = DefinitionWriter.toYaml(Definitions.validImport().layouts(List.of(layout.build()))
                .duplicateProtection(new DuplicateProtection(false, PostSuccessAction.DELETE)).build());
        String move = DefinitionWriter.toYaml(Definitions.validImport().layouts(List.of(layout.build()))
                .duplicateProtection(new DuplicateProtection(false, PostSuccessAction.move("done/"))).build());

        assertThat(none).contains("- UPPER", "- type: PAD_LEFT", "length: 9", "char: \"0\"")
                .doesNotContain("postSuccessAction");
        assertThat(delete).contains("postSuccessAction: DELETE");
        assertThat(move).contains("postSuccessAction:", "type: MOVE", "remoteFolder: done/");
    }

    @Test
    @DisplayName("AC-286 a translation to null is written with an explicit null and loads back")
    void translationToNull() {
        IntegrationDefinition definition = Definitions.validImport().layouts(List.of(Definitions.csvLayout("alta")
                .fields(List.of(FieldDefinition.builder("sexo")
                        .translation(new Translation("X", null, TranslationDirection.IN)).build())).build())).build();

        assertThat(DefinitionWriter.toJson(definition))
                .contains("\"from\" : \"X\",", "\"to\" : null,", "\"direction\" : \"IN\"");
        assertRoundTrip(definition);
    }

    @Test
    @DisplayName("AC-287 strings that YAML would read as another type come back as strings")
    void ambiguousYamlStrings() {
        IntegrationDefinition definition = Definitions.validImport().layouts(List.of(Definitions.csvLayout("alta")
                .fields(List.of(FieldDefinition.builder("sexo").defaultValue("null")
                        .translation("true", "yes").translation("1.0", "on").translation("no", "off")
                        .onUnmatched(org.tie.config.OnUnmatched.PASS)
                        .validations(Validations.regex("a#b: [c]")).build())).build())).build();

        assertThat(DefinitionLoader.fromYaml(DefinitionWriter.toYaml(definition))).isEqualTo(definition);
    }

    @Test
    @DisplayName("AC-288 writing is deterministic, also for equal definitions built in another order")
    void deterministic() {
        IntegrationDefinition a = Definitions.validImport().layouts(List.of(Definitions.csvLayout("alta")
                .allowedFormats(FileFormat.CSV, FileFormat.XLSX).build())).build();
        IntegrationDefinition b = Definitions.validImport().layouts(List.of(Definitions.csvLayout("alta")
                .allowedFormats(FileFormat.XLSX, FileFormat.CSV).build())).build();

        assertThat(a).isEqualTo(b);
        assertThat(DefinitionWriter.toJson(a)).isEqualTo(DefinitionWriter.toJson(a)).isEqualTo(DefinitionWriter.toJson(b));
        assertThat(DefinitionWriter.toYaml(a)).isEqualTo(DefinitionWriter.toYaml(b));
    }

    @Test
    @DisplayName("AC-289 a null definition is a NullPointerException")
    void nullDefinition() {
        assertThatThrownBy(() -> DefinitionWriter.toJson(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DefinitionWriter.toYaml(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("AC-290 durations, charsets, locales, paths and decimals have their text forms")
    void valueForms() {
        SftpChannel sftp = Definitions.validSftp().password("${secret:sftp}").timeout(Duration.ofSeconds(90)).build();
        IntegrationDefinition definition = Definitions.validImport()
                .layouts(List.of(Definitions.csvLayout("alta")
                        .csv(CsvOptions.builder().encoding(java.nio.charset.Charset.forName("ISO-8859-1")).build())
                        .xlsx(org.tie.config.XlsxOptions.builder().locale(Locale.forLanguageTag("es-ES")).build())
                        .allowedFormats(FileFormat.CSV, FileFormat.XLSX).build()))
                .inputChannels(List.of(LocalDirectoryChannel.of(Path.of("/data/in")), sftp))
                .rejectionThreshold(new RejectionThreshold(null, new BigDecimal("12.5")))
                .deliveryStrategy(org.tie.config.DeliveryStrategy.VALIDATE_THEN_DELIVER)
                .build();

        String json = DefinitionWriter.toJson(definition);

        assertThat(json).contains("\"timeout\" : \"PT1M30S\"", "\"encoding\" : \"ISO-8859-1\"", "\"locale\" : \"es-ES\"",
                "\"directory\" : \"/data/in\"", "\"maxRejectedPercent\" : 12.5");
        assertRoundTrip(definition);
    }

    @Test
    @DisplayName("AC-310 an expression loads from YAML and is written as is")
    void expressionLoadedAndWritten() {
        String source = "value != null ? value : (tie:age(fechaNacimiento) < 16 ? parent.documento : null)";
        IntegrationDefinition definition = Definitions.validImport().layouts(List.of(Definitions.csvLayout("alta")
                .fields(List.of(FieldDefinition.builder("documento").expression(source).build())).build())).build();

        String yaml = DefinitionWriter.toYaml(definition);

        assertThat(DefinitionLoader.fromYaml(yaml).layouts().getFirst().fields().getFirst().expression()).isEqualTo(source);
        assertRoundTrip(definition);
    }

    @Test
    @DisplayName("AC-313 an invalid definition is not written: ConfigurationException with every problem")
    void invalidDefinition() {
        IntegrationDefinition invalid = Definitions.validImport().layouts(List.of(Layout.builder(null)
                .fileNamePattern("x_{timestamp:yyyyMMdd}").allowedFormats(FileFormat.CSV)
                .field(FieldDefinition.builder("a").copyFrom("ghost")).build())).build();

        for (Runnable write : List.<Runnable>of(() -> DefinitionWriter.toJson(invalid), () -> DefinitionWriter.toYaml(invalid))) {
            assertThatThrownBy(write::run).isInstanceOf(ConfigurationException.class)
                    .satisfies(e -> assertThat(((ConfigurationException) e).getProblems()).hasSizeGreaterThanOrEqualTo(2)
                            .anySatisfy(problem -> assertThat(problem).contains("copyFrom 'ghost' does not exist"))
                            .anySatisfy(problem -> assertThat(problem).contains("name")));
        }
    }
}
