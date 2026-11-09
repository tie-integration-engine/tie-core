package org.tie.config.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tie.ConfigurationException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The published schema and the loader must agree: valid documents pass both, structurally invalid
 * documents fail both.
 */
class SchemaCoherenceTest {

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = DefinitionLoader.schema()) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        }
    }

    private static List<Error> schemaErrors(String yaml) {
        return schema.validate(YAMLMapper.builder().build().readTree(yaml));
    }

    @ParameterizedTest
    @ValueSource(strings = {"full-import.yaml", "export.yaml", "spec-example.yaml", "spec-example.json"})
    void validDocumentsPassTheSchemaAndTheLoader(String name) {
        String document = DefinitionLoaderTest.resource(name);

        assertThat(schemaErrors(document)).isEmpty();
        if (name.endsWith(".json")) {
            DefinitionLoader.fromJson(document);
        } else {
            DefinitionLoader.fromYaml(document);
        }
    }

    static Stream<Arguments> invalidDocuments() {
        String base = DefinitionLoaderTest.resource("spec-example.yaml");
        return Stream.of(
                Arguments.of("unknown root property", base + "colour: blue\n"),
                Arguments.of("unknown field property", base.replace("- name: nif", "- name: nif\n        colum: A")),
                Arguments.of("unknown channel property", base.replace("timeout: PT30S", "timeout: PT30S\n    colour: x")),
                Arguments.of("wrong enum value", base.replace("direction: IMPORT", "direction: SIDEWAYS")),
                Arguments.of("unknown defaultMode", base.replace("- name: nif", "- name: nif\n        defaultMode: RAW")),
                Arguments.of("unknown copyMode", base.replace("- name: nif", "- name: nif\n        copyMode: RAW")),
                Arguments.of("unsupported version", base.replace("version: 1", "version: 2")),
                Arguments.of("missing version", base.replace("version: 1\n", "")),
                Arguments.of("wrong scalar type", base.replace("watermark: true", "watermark: yes please")),
                Arguments.of("timeout as a number", base.replace("timeout: PT30S", "timeout: 30")),
                Arguments.of("MOVE without folder", base.replace("{ type: MOVE, remoteFolder: done/ }", "MOVE")),
                Arguments.of("PAD_LEFT as a string", base.replace("{ type: PAD_LEFT, length: 9, char: \"0\" }", "PAD_LEFT")),
                Arguments.of("unknown text transform", base.replace("[UPPER,", "[SHOUT,")),
                Arguments.of("separator of two characters", base.replace("separator: \";\"", "separator: \";;\"")),
                Arguments.of("unknown channel type", base.replace("type: SFTP", "type: FTP")),
                Arguments.of("two SFTP credentials", base.replace("password: ${secret:sftp}",
                        "password: ${secret:sftp}\n    privateKey: PEM")),
                Arguments.of("null value", base.replace("columnLocation: HEADER", "columnLocation: null")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void invalidDocumentsFailTheSchemaAndTheLoader(String description, String yaml) {
        assertThat(schemaErrors(yaml)).as("schema errors").isNotEmpty();
        assertThatThrownBy(() -> DefinitionLoader.fromYaml(yaml)).isInstanceOf(ConfigurationException.class);
    }
}
