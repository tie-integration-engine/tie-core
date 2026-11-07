package org.tie.internal.load;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Strict reader over a document node: every typed accessor reports a problem (with the property
 * path) instead of throwing, and marks the property as known. {@link #finish()} reports the
 * properties nobody read as unknown (§5.5).
 */
final class Node {

    private final JsonNode node;
    private final String path;
    private final List<String> problems;
    private final Set<String> known = new HashSet<>();

    Node(JsonNode node, String path, List<String> problems) {
        this.node = node;
        this.path = path;
        this.problems = problems;
    }

    String path() {
        return path;
    }

    JsonNode raw() {
        return node;
    }

    /** Whether the property is present (an explicit {@code null} counts as present). */
    boolean has(String name) {
        return node.has(name);
    }

    void problem(String name, String message) {
        problems.add(child(name) + ": " + message);
    }

    // ---------------------------------------------------------------- scalars

    String string(String name) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            return typeError(name, "a string", value);
        }
        return value.stringValue();
    }

    /** Like {@link #string(String)} but an explicit {@code null} is a valid value. */
    String nullableString(String name) {
        known.add(name);
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : string(name);
    }

    Integer integer(String name) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            return typeError(name, "an integer", value);
        }
        return value.intValue();
    }

    Long longValue(String name) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            return typeError(name, "an integer", value);
        }
        return value.longValue();
    }

    BigDecimal decimal(String name) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            return typeError(name, "a number", value);
        }
        return value.decimalValue();
    }

    Boolean bool(String name) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isBoolean()) {
            return typeError(name, "a boolean", value);
        }
        return value.booleanValue();
    }

    boolean bool(String name, boolean defaultValue) {
        Boolean value = bool(name);
        return value == null ? defaultValue : value;
    }

    Character character(String name) {
        String value = string(name);
        if (value == null) {
            return null;
        }
        if (value.length() != 1) {
            problem(name, "expected a single character, got '" + value + "'");
            return null;
        }
        return value.charAt(0);
    }

    <E extends Enum<E>> E enumValue(String name, Class<E> type) {
        JsonNode value = value(name);
        return value == null ? null : Node.enumValue(value, child(name), type, problems);
    }

    Charset charset(String name) {
        String value = string(name);
        if (value == null) {
            return null;
        }
        try {
            return Charset.forName(value);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            problem(name, "unsupported encoding '" + value + "'");
            return null;
        }
    }

    Path filePath(String name) {
        String value = string(name);
        if (value == null) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            problem(name, "invalid path '" + value + "'");
            return null;
        }
    }

    Duration duration(String name) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            return typeError(name, "an ISO-8601 duration such as PT30S", value);
        }
        try {
            return Duration.parse(value.stringValue());
        } catch (DateTimeParseException e) {
            problem(name, "expected an ISO-8601 duration such as PT30S, got '" + value.stringValue() + "'");
            return null;
        }
    }

    // ---------------------------------------------------------------- containers

    /** The nested object, or null if absent or not an object (with a problem). */
    Node object(String name) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isObject()) {
            return typeError(name, "an object", value);
        }
        return new Node(value, child(name), problems);
    }

    /** Items of an array property mapped with {@code item}; items that map to null are dropped. */
    <T> List<T> list(String name, Function<Item, T> item) {
        JsonNode value = value(name);
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            return typeError(name, "an array", value);
        }
        List<T> result = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            T mapped = item.apply(new Item(value.get(i), child(name) + "[" + i + "]", problems));
            if (mapped != null) {
                result.add(mapped);
            }
        }
        return result;
    }

    /** A present property of any shape, for values that are a string or an object. */
    Item item(String name) {
        known.add(name);
        return new Item(node.get(name), child(name), problems);
    }

    /** Reports every property that no accessor read. */
    void finish() {
        for (String name : node.propertyNames()) {
            if (!known.contains(name)) {
                problems.add((path.isEmpty() ? "" : path + ": ") + "unknown property '" + name + "'");
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private JsonNode value(String name) {
        known.add(name);
        JsonNode value = node.get(name);
        if (value != null && value.isNull()) {
            problem(name, "must not be null");
            return null;
        }
        return value;
    }

    private <T> T typeError(String name, String expected, JsonNode value) {
        problem(name, "expected " + expected + ", got " + describe(value));
        return null;
    }

    private String child(String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    static String describe(JsonNode value) {
        if (value.isString()) {
            return "'" + value.stringValue() + "'";
        }
        if (value.isObject()) {
            return "an object";
        }
        if (value.isArray()) {
            return "an array";
        }
        return value.toString();
    }

    static <E extends Enum<E>> E enumValue(JsonNode value, String path, Class<E> type, List<String> problems) {
        String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
        if (value.isString()) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value.stringValue())) {
                    return constant;
                }
            }
        }
        problems.add(path + ": expected one of " + allowed + ", got " + describe(value));
        return null;
    }

    /** One array item: either a scalar or an object. */
    record Item(JsonNode value, String path, List<String> problems) {

        boolean isString() {
            return value.isString();
        }

        boolean isObject() {
            return value.isObject();
        }

        String stringValue() {
            return value.stringValue();
        }

        Node asObject() {
            return new Node(value, path, problems);
        }

        <E extends Enum<E>> E asEnum(Class<E> type) {
            return Node.enumValue(value, path, type, problems);
        }

        String asString() {
            if (!value.isString()) {
                problems.add(path + ": expected a string, got " + describe(value));
                return null;
            }
            return value.stringValue();
        }

        <T> T error(String expected) {
            problems.add(path + ": expected " + expected + ", got " + describe(value));
            return null;
        }
    }
}
