package org.tie.config.load;

import java.util.List;
import java.util.Objects;
import org.tie.ConfigurationException;
import org.tie.config.IntegrationDefinition;
import org.tie.internal.load.DefinitionNodes;
import org.tie.internal.validation.DefinitionValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Writes an {@link IntegrationDefinition} as the JSON or YAML document of §5.5, the inverse of
 * {@link DefinitionLoader}. The definition is validated first. Keys follow the order of the JSON schema and
 * options with their default value are left out, so equal definitions give the same text. Literal credentials
 * ({@code password}, {@code privateKey}, {@code privateKeyPassphrase} that are not a {@code ${secret:name}}
 * reference) are written as {@code ***}: such a document does not load back until they are replaced (INV-10).
 * Without literal credentials, loading the document gives a definition equal to the original (INV-26).
 */
public final class DefinitionWriter {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .build();

    private DefinitionWriter() {
    }

    /**
     * The definition as a JSON document that {@link DefinitionLoader#fromJson(String)} reads back.
     *
     * @throws ConfigurationException if the definition is not valid, with every problem
     */
    public static String toJson(IntegrationDefinition definition) {
        return JSON.writeValueAsString(document(definition)) + "\n";
    }

    /**
     * The definition as a YAML document that {@link DefinitionLoader#fromYaml(String)} reads back.
     *
     * @throws ConfigurationException if the definition is not valid, with every problem
     */
    public static String toYaml(IntegrationDefinition definition) {
        return YAML.writeValueAsString(document(definition));
    }

    private static Object document(IntegrationDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        List<String> problems = DefinitionValidator.validate(definition);
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        return DefinitionNodes.document(definition);
    }
}
