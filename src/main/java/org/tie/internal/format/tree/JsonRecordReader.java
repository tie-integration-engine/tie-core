package org.tie.internal.format.tree;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tie.FileErrorType;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.internal.format.DecodingReader;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.NestedRecords;
import org.tie.internal.format.ReadRecord;
import org.tie.internal.format.RecordReader;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records of a JSON file: the elements of the root array, of the array under {@code recordProperty}, or, with
 * {@code firstArray} and a root object, of its first top-level property that is an array. The file is streamed; each record is materialized with its children
 * (INV-13 applies to the file, not to one record). Nested objects are flattened with dotted paths
 * (AC-39); an array must be a declared nested layout (AC-161).
 */
public final class JsonRecordReader implements RecordReader {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            // records are read one by one from the middle of the array
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final JsonParser parser;
    private final String recordProperty;
    private final boolean firstArray;
    private final Layout root;
    private final TreeLayouts layouts;
    private boolean started;
    private boolean finished;

    public JsonRecordReader(InputStream in, JsonOptions options, Layout root, List<Layout> layouts) {
        this.root = root;
        this.layouts = new TreeLayouts(layouts);
        try {
            this.parser = MAPPER.createParser(new DecodingReader(in, options.encoding()));
        } catch (JacksonException e) {
            throw failure(e);
        }
        this.recordProperty = options.recordProperty();
        this.firstArray = options.firstArray();
    }

    @Override
    public ReadRecord next() {
        if (finished) {
            return null;
        }
        try {
            if (!started) {
                started = true;
                openRecordsArray();
            }
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_ARRAY || token == null) {
                finished = true;
                return null;
            }
            if (token != JsonToken.START_OBJECT) {
                throw FileFailure.structural("every record must be a JSON object, found " + token + location(), null);
            }
            JsonNode node = MAPPER.readTree(parser);
            return record(node, root);
        } catch (JacksonException e) {
            throw failure(e);
        }
    }

    private void openRecordsArray() {
        JsonToken token = parser.nextToken();
        if (recordProperty == null) {
            if (token == JsonToken.START_ARRAY) {
                return;
            }
            if (firstArray && token == JsonToken.START_OBJECT) {
                openFirstArray();
                return;
            }
            throw FileFailure.structural("expected a JSON array of records at the root" + location(), null);
        }
        if (token != JsonToken.START_OBJECT) {
            throw FileFailure.structural("expected a JSON object with property '" + recordProperty + "'" + location(), null);
        }
        while ((token = parser.nextToken()) == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if (name.equals(recordProperty)) {
                if (value != JsonToken.START_ARRAY) {
                    throw FileFailure.structural("property '" + recordProperty + "' must be an array of records"
                            + location(), null);
                }
                return;
            }
            parser.skipChildren();
        }
        throw FileFailure.structural("property '" + recordProperty + "' not found", null);
    }

    /** Moves to the first top-level property of the root object whose value is an array; the others are skipped. */
    private void openFirstArray() {
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            if (parser.nextToken() == JsonToken.START_ARRAY) {
                return;
            }
            parser.skipChildren();
        }
        throw FileFailure.structural("expected an array property in the root JSON object", null);
    }

    private ReadRecord record(JsonNode node, Layout layout) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Layout> childLayouts = layouts.children(layout);
        Map<String, NestedRecords> children = new LinkedHashMap<>();
        flatten(node, "", values, childLayouts, children);
        List<NestedRecords> nested = new ArrayList<>();
        for (Map.Entry<String, Layout> child : childLayouts.entrySet()) {
            nested.add(children.getOrDefault(child.getKey(), new NestedRecords(child.getValue(), false, List.of())));
        }
        return new ReadRecord(TreeLayouts.input(layout, values), null, nested);
    }

    private void flatten(JsonNode node, String prefix, Map<String, String> values, Map<String, Layout> childLayouts,
                         Map<String, NestedRecords> children) {
        for (String name : node.propertyNames()) {
            JsonNode value = node.get(name);
            String path = TreeLayouts.path(prefix, name);
            Layout childLayout = childLayouts.get(path);
            if (childLayout != null) {
                if (value.isNull()) {
                    continue;
                }
                if (!value.isArray()) {
                    throw FileFailure.structural("'" + path + "' must be an array of '" + childLayout.name()
                            + "' records", null);
                }
                List<ReadRecord> records = new ArrayList<>();
                for (JsonNode element : value) {
                    if (!element.isObject()) {
                        throw FileFailure.structural("every element of '" + path + "' must be an object", null);
                    }
                    records.add(record(element, childLayout));
                }
                children.put(path, new NestedRecords(childLayout, true, records));
            } else if (value.isObject()) {
                flatten(value, path, values, childLayouts, children);
            } else if (value.isArray()) {
                throw FileFailure.structural("array '" + path + "' is not declared as a nested layout", null);
            } else if (!value.isNull()) {
                values.put(path, scalar(value));
            }
        }
    }

    private static String scalar(JsonNode value) {
        if (value.isString()) {
            return value.stringValue();
        }
        if (value.isNumber() && !value.isIntegralNumber()) {
            return value.decimalValue().toPlainString();
        }
        return value.asString();
    }

    private String location() {
        TokenStreamLocation location = parser.currentLocation();
        return location == null || location.getLineNr() < 1 ? ""
                : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }

    private static FileFailure failure(JacksonException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DecodingReader.EncodingException encoding) {
                return new FileFailure(FileErrorType.ENCODING, encoding.getMessage(), null, e);
            }
        }
        TokenStreamLocation location = e.getLocation();
        String where = location == null || location.getLineNr() < 1 ? ""
                : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
        return new FileFailure(FileErrorType.STRUCTURAL, "invalid JSON: " + e.getOriginalMessage() + where, null, e);
    }

    @Override
    public void close() {
        try {
            parser.close();
        } catch (JacksonException ignored) {
            // closing only
        }
    }
}
