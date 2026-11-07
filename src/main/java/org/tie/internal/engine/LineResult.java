package org.tie.internal.engine;

import java.util.List;
import java.util.Map;
import org.tie.LineError;

/** Outcome of the field engine for one line: typed values, or every error found (INV-7). */
public sealed interface LineResult {

    Map<String, String> rawValues();

    /** The line has no field error; {@code values} goes to the consumer. */
    record Accepted(FieldValues values) implements LineResult {
        @Override
        public Map<String, String> rawValues() {
            return values.rawValues();
        }
    }

    /** At least one field error; the line never reaches the consumer. */
    record Rejected(List<LineError> errors, Map<String, String> rawValues) implements LineResult {
        public Rejected {
            errors = List.copyOf(errors);
        }
    }
}
