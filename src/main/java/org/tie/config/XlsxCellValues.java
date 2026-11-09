package org.tie.config;

/** What text an XLSX cell produces when it is read (§4.1 «Celdas XLSX»). Ignored on export. */
public enum XlsxCellValues {
    /** The stored value, normalized: plain numbers, ISO dates, {@code true}/{@code false} (the default). */
    CANONICAL,
    /** The text Excel displays, per the cell's number format and {@link XlsxOptions#locale()}. */
    FORMATTED
}
