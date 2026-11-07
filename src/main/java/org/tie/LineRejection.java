package org.tie;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A rejected line (§4.2). {@code rawValues} are the values as read, before any engine step, with the
 * same keys as {@link RecordView#rawValues()}; a child carries its own, not its parent's.
 *
 * @param rowNumber physical row (CSV/XLSX), null for JSON/XML
 */
public record LineRejection(int lineNumber, Integer rowNumber, String layout, List<LineError> errors,
                            String sourceFileName, Integer parentLineNumber, Map<String, String> rawValues) {

    public LineRejection {
        errors = List.copyOf(errors);
        rawValues = Collections.unmodifiableMap(new LinkedHashMap<>(rawValues));
    }
}
