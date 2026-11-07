package org.tie.internal.format.tabular;

import java.util.List;
import java.util.function.Consumer;
import org.tie.Warning;
import org.tie.config.Layout;
import org.tie.internal.format.ReadRecord;
import org.tie.internal.format.RecordReader;

/**
 * Records of a CSV/XLSX file (§4.1 "Filas, cabecera y preámbulo"): with a header, it is row
 * {@code firstDataRow − 1}; data starts at {@code firstDataRow}; earlier rows are preamble and are
 * ignored without looking at them. Blank rows are always skipped; with {@code skipBlankRecords},
 * rows whose cells are all blank too.
 */
public final class TabularRecordReader implements RecordReader {

    private final TabularSource source;
    private final Layout layout;
    private final boolean hasHeader;
    private final int firstDataRow;
    private final boolean skipBlankRecords;
    private final Consumer<Warning> warnings;
    private final String fileName;
    private boolean started;
    private ColumnMapping mapping;
    private TabularRow pending;

    public TabularRecordReader(TabularSource source, Layout layout, boolean hasHeader, int firstDataRow,
                               boolean skipBlankRecords, Consumer<Warning> warnings, String fileName) {
        this.source = source;
        this.layout = layout;
        this.hasHeader = hasHeader;
        this.firstDataRow = firstDataRow;
        this.skipBlankRecords = skipBlankRecords;
        this.warnings = warnings;
        this.fileName = fileName;
    }

    @Override
    public ReadRecord next() {
        if (!started) {
            started = true;
            mapping = start();
        }
        if (mapping == null) {
            return null;
        }
        while (true) {
            TabularRow row = pending != null ? pending : source.nextRow();
            pending = null;
            if (row == null) {
                return null;
            }
            if (row.rowNumber() < firstDataRow || row.blank() || skipBlankRecords && row.allCellsBlank()) {
                continue;
            }
            return new ReadRecord(mapping.map(row), row.rowNumber(), List.of());
        }
    }

    /** The column mapping, or null when the file ends before its header row: it has no records. */
    private ColumnMapping start() {
        List<String> header = null;
        int headerRow = firstDataRow - 1;
        if (hasHeader) {
            header = List.of();
            TabularRow row;
            while ((row = source.nextRow()) != null && row.rowNumber() < headerRow) {
                // preamble
            }
            if (row == null) {
                return null;
            }
            if (row.rowNumber() == headerRow) {
                header = row.cells();
            } else {
                pending = row;
            }
        }
        return ColumnMapping.create(layout, header, headerRow, source.canonicalValues(), warnings, fileName);
    }

    @Override
    public void close() {
        source.close();
    }
}
