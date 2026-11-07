package org.tie.internal.format.tabular;

/** Physical rows of a CSV or XLSX file, read incrementally. */
public interface TabularSource extends AutoCloseable {

    /**
     * The next row in file order, or null at the end. Missing rows may be skipped: callers rely on
     * {@link TabularRow#rowNumber()}, not on the call count.
     *
     * @throws org.tie.internal.format.FileFailure if the file cannot be read
     */
    TabularRow nextRow();

    /** Whether values come from XLSX cells, which also accept the ISO/canonical forms. */
    boolean canonicalValues();

    @Override
    void close();
}
