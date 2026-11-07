package org.tie.internal.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.tie.RecordView;

/**
 * Typed field values of one line. A present key with a null value means "empty"; an absent key
 * means "do not touch" (INV-5).
 */
public final class FieldValues implements RecordView {

    private final Map<String, Object> values;
    private final Map<String, String> rawValues;

    FieldValues(Map<String, Object> values, Map<String, String> rawValues) {
        this.values = values;
        this.rawValues = rawValues;
    }

    /** An immutable copy with the entries in {@code order}. */
    static FieldValues frozen(Map<String, Object> values, Iterable<String> order, Map<String, String> rawValues) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String name : order) {
            if (values.containsKey(name)) {
                ordered.put(name, values.get(name));
            }
        }
        return new FieldValues(Collections.unmodifiableMap(ordered), rawValues);
    }

    @Override
    public boolean has(String field) {
        return values.containsKey(field);
    }

    @Override
    public Object get(String field) {
        return values.get(field);
    }

    @Override
    public <T> T get(String field, Class<T> type) {
        Object value = values.get(field);
        if (value != null && !type.isInstance(value)) {
            throw new ClassCastException("field '" + field + "' is a " + value.getClass().getSimpleName()
                    + ", not a " + type.getSimpleName());
        }
        return type.cast(value);
    }

    /** Field names and values in declaration order; unmodifiable once frozen. */
    public Map<String, Object> asMap() {
        return values;
    }

    @Override
    public Map<String, String> rawValues() {
        return rawValues;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FieldValues other && values.equals(other.values) && rawValues.equals(other.rawValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, rawValues);
    }

    @Override
    public String toString() {
        return "FieldValues" + values.keySet();
    }
}
