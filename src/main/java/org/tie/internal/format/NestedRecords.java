package org.tie.internal.format;

import java.util.List;
import org.tie.config.Layout;

/**
 * The sub-records of one child layout inside a parent record.
 *
 * @param declared whether the container is present: {@code []} is declared, an absent key or
 *                 {@code null} is not (AC-79)
 */
public record NestedRecords(Layout layout, boolean declared, List<ReadRecord> records) {

    public NestedRecords {
        records = List.copyOf(records);
    }
}
