package org.tie.internal.format.write;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import org.tie.config.FieldDefinition;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.internal.engine.Values;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON export: the root array, or an object with the array under {@code recordProperty}. Dotted paths
 * become nested objects; NATIVE numbers and booleans keep their JSON type; an empty value is
 * {@code null} (AC-181).
 */
final class JsonRecordWriter implements RecordWriter {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Writer writer;
    private final JsonGenerator generator;
    private final Layout layout;
    private final String recordProperty;

    JsonRecordWriter(OutputStream out, Layout layout) {
        JsonOptions options = layout.formatOptions().json();
        this.writer = new OutputStreamWriter(out, options.encoding());
        this.generator = MAPPER.createGenerator(writer);
        this.layout = layout;
        this.recordProperty = options.recordProperty();
        if (recordProperty != null) {
            generator.writeStartObject();
            generator.writeName(recordProperty);
        }
        generator.writeStartArray();
    }

    @Override
    public void write(OutputRecord record) {
        MAPPER.writeValue(generator, node(record, layout));
    }

    private static ObjectNode node(OutputRecord record, Layout layout) {
        ObjectNode node = NODES.objectNode();
        for (FieldDefinition field : layout.fields()) {
            String path = field.column() != null ? field.column() : field.name();
            put(node, path, value(record.values().get(field.name())));
        }
        for (OutputRecord.Children children : record.children()) {
            ArrayNode array = NODES.arrayNode();
            for (OutputRecord child : children.records()) {
                array.add(node(child, children.layout()));
            }
            put(node, children.layout().nestedKey(), array);
        }
        return node;
    }

    private static void put(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            if (next == null) {
                next = current.putObject(parts[i]);
            } else if (!next.isObject()) {
                throw new IllegalStateException("path '" + path + "' conflicts with another field");
            }
            current = (ObjectNode) next;
        }
        if (current.has(parts[parts.length - 1])) {
            throw new IllegalStateException("path '" + path + "' conflicts with another field");
        }
        current.set(parts[parts.length - 1], value);
    }

    private static JsonNode value(Object value) {
        return switch (value) {
            case null -> NODES.nullNode();
            case Long number -> NODES.numberNode(number);
            case BigDecimal number -> NODES.numberNode(number);
            case Boolean bool -> NODES.booleanNode(bool);
            case String text -> NODES.stringNode(text);
            default -> NODES.stringNode(Values.canonical(value));
        };
    }

    @Override
    public void finish() {
        generator.writeEndArray();
        if (recordProperty != null) {
            generator.writeEndObject();
        }
        generator.flush();
    }

    @Override
    public void close() {
        generator.close();
    }
}
