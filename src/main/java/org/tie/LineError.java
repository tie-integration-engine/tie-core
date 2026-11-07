package org.tie;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One error of a rejected line (§4.2, §5.4). {@code field} is null for line-level errors. The
 * argument values are text; arguments without a value are absent.
 */
public record LineError(String field, ErrorCode code, Map<String, String> args, String message) {

    public LineError {
        Objects.requireNonNull(code, "code");
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        message = message == null ? code.message(args) : message;
    }

    /**
     * Builds an error from alternating argument names and values; null values are dropped.
     *
     * @throws IllegalArgumentException if a name is not one of the code's argument names
     */
    public static LineError of(String field, ErrorCode code, String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("arguments must be name/value pairs");
        }
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            String name = namesAndValues[i];
            if (!code.argumentNames().contains(name)) {
                throw new IllegalArgumentException(code + " has no argument '" + name + "'");
            }
            if (namesAndValues[i + 1] != null) {
                args.put(name, namesAndValues[i + 1]);
            }
        }
        return new LineError(field, code, args, null);
    }
}
