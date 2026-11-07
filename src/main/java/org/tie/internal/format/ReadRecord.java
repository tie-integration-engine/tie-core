package org.tie.internal.format;

import java.util.List;
import org.tie.internal.engine.LineInput;

/**
 * One record as read from a file, ready for the field engine.
 *
 * @param rowNumber physical row (CSV/XLSX); null for JSON and XML
 * @param children  nested sub-records per child layout, in layout declaration order
 */
public record ReadRecord(LineInput input, Integer rowNumber, List<NestedRecords> children) {

    public ReadRecord {
        children = List.copyOf(children);
    }
}
