package org.tie;

import java.util.Map;

/**
 * Read-only view of a record's field values. During the field engine it holds the fields computed
 * so far, already typed (§4.2).
 */
public interface RecordView {

    /** Whether the field is present; absent means "do not touch" (INV-5). */
    boolean has(String field);

    /**
     * The typed value; {@code null} when the field is present and empty, or absent. It is of the Java type of
     * the field's type (§4.2), except for a field with {@code defaultMode TEXT} that took its default value:
     * then it is that text, a {@code String} (INV-24).
     */
    Object get(String field);

    /**
     * The typed value cast to {@code type}.
     *
     * @throws ClassCastException if the value is not of that type, which also happens for the String of a
     *                            {@code defaultMode TEXT} default (INV-24)
     */
    <T> T get(String field, Class<T> type);

    /** Values as read from the file, before any engine step. */
    Map<String, String> rawValues();
}
