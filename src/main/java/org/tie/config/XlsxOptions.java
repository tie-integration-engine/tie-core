package org.tie.config;

/** XLSX options; {@code sheet == null} means the first sheet. */
public record XlsxOptions(String sheet, boolean hasHeader, int firstDataRow) {

    public static final XlsxOptions DEFAULTS = new XlsxOptions(null, true, 2);
}
