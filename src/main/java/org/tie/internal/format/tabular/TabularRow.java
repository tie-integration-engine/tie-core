package org.tie.internal.format.tabular;

import java.util.List;

/**
 * One physical row of a CSV or XLSX file, numbered from 1 over every row of the file (§4.1).
 *
 * @param blank whether the row has no separators (CSV) or no cell with a value (XLSX); such rows are
 *              skipped and never consume a line number (AC-31)
 */
public record TabularRow(int rowNumber, List<String> cells, boolean blank) {

    public TabularRow {
        cells = List.copyOf(cells);
    }

    /** Whether every cell is empty or whitespace ({@code ;;;}, {@code " ";;}). */
    public boolean allCellsBlank() {
        return cells.stream().allMatch(String::isBlank);
    }
}
