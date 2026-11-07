package org.tie.config.load;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.tie.ConfigurationException;
import org.tie.config.IntegrationDefinition;
import org.tie.internal.load.DefinitionMapper;
import org.tie.internal.validation.DefinitionValidator;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads an {@link IntegrationDefinition} from a JSON or YAML document (§5.5): {@code version: 1}
 * at the root and the structure of §4.1 in camelCase. The JSON schema is published as
 * {@value #SCHEMA_RESOURCE}.
 *
 * <p>Unknown properties, wrong types and an invalid definition throw a {@link ConfigurationException}
 * listing every problem. SPI checks (registered transformers, secret provider) are done by
 * {@code Tie.validate} and on execution.
 */
public final class DefinitionLoader {

    /** Classpath location of the JSON schema of the document. */
    public static final String SCHEMA_RESOURCE = "/tie-definition.schema.json";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private DefinitionLoader() {
    }

    /** Loads a JSON document; the stream is read fully but not closed. */
    public static IntegrationDefinition fromJson(InputStream json) {
        return load(JSON, "JSON", Objects.requireNonNull(json, "json"));
    }

    public static IntegrationDefinition fromJson(String json) {
        return fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** Loads a YAML document; the stream is read fully but not closed. */
    public static IntegrationDefinition fromYaml(InputStream yaml) {
        return load(YAML, "YAML", Objects.requireNonNull(yaml, "yaml"));
    }

    public static IntegrationDefinition fromYaml(String yaml) {
        return fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /** The JSON schema of the document; the caller closes the stream. */
    public static InputStream schema() {
        return Objects.requireNonNull(DefinitionLoader.class.getResourceAsStream(SCHEMA_RESOURCE), SCHEMA_RESOURCE);
    }

    private static IntegrationDefinition load(ObjectMapper mapper, String format, InputStream input) {
        JsonNode root;
        try {
            root = mapper.readTree(input);
        } catch (JacksonException e) {
            throw new ConfigurationException("could not parse " + format + " document: " + describe(e));
        }
        DefinitionMapper.Result result = DefinitionMapper.map(root);
        List<String> problems = new ArrayList<>(result.problems());
        if (result.definition() != null && problems.isEmpty()) {
            problems.addAll(DefinitionValidator.validate(result.definition()));
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        return result.definition();
    }

    private static String describe(JacksonException e) {
        TokenStreamLocation location = e.getLocation();
        String message = e.getOriginalMessage();
        if (location == null || location.getLineNr() < 1) {
            return message;
        }
        return message + " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }
}
