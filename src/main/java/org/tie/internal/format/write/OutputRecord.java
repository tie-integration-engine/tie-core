package org.tie.internal.format.write;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tie.config.Layout;

/**
 * One record to write: values per field name (a String, a typed value, or null for empty) and its
 * nested children per child layout.
 */
public record OutputRecord(Map<String, Object> values, List<Children> children) {

    public OutputRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        children = List.copyOf(children);
    }

    public record Children(Layout layout, List<OutputRecord> records) {
        public Children {
            records = List.copyOf(records);
        }
    }
}
