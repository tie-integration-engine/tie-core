package org.tie.internal.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the readers hand to the engine for one record.
 *
 * @param fieldInputs       value read for each input field, keyed by field name; a missing key or a
 *                          null value is an empty value (AC-30)
 * @param rawValues         the record as read, keyed as in §4.2 (header, position/letter or path)
 * @param canonicalFallback whether DATE/DATETIME/DECIMAL also accept the ISO/canonical form (XLSX)
 */
public record LineInput(Map<String, String> fieldInputs, Map<String, String> rawValues, boolean canonicalFallback) {

    public LineInput {
        fieldInputs = Collections.unmodifiableMap(new LinkedHashMap<>(fieldInputs));
        rawValues = Collections.unmodifiableMap(new LinkedHashMap<>(rawValues));
    }

    /** Input where each field is read from the raw value with the same key. */
    public static LineInput of(Map<String, String> values) {
        return new LineInput(values, values, false);
    }
}
