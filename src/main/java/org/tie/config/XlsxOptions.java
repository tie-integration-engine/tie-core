package org.tie.config;

import java.util.Locale;

/**
 * XLSX options (§4.1). {@code sheet == null} means the first sheet. {@code skipBlankRecords},
 * {@code cellValues} and {@code locale} only apply to reading; {@code cellValues == null} means
 * {@link XlsxCellValues#CANONICAL} and {@code locale == null} means {@link Locale#ROOT}. Build it with
 * {@link #builder()}.
 */
public record XlsxOptions(String sheet, boolean hasHeader, int firstDataRow, boolean skipBlankRecords,
                          XlsxCellValues cellValues, Locale locale) {

    public static final XlsxOptions DEFAULTS = builder().build();

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().sheet(sheet).hasHeader(hasHeader).firstDataRow(firstDataRow)
                .skipBlankRecords(skipBlankRecords).cellValues(cellValues).locale(locale);
    }

    /** {@code cellValues} if set, else {@link XlsxCellValues#CANONICAL}. */
    public XlsxCellValues effectiveCellValues() {
        return cellValues == null ? XlsxCellValues.CANONICAL : cellValues;
    }

    /** {@code locale} if set, else {@link Locale#ROOT}. */
    public Locale effectiveLocale() {
        return locale == null ? Locale.ROOT : locale;
    }

    public static final class Builder {
        private String sheet;
        private boolean hasHeader = true;
        private int firstDataRow = 2;
        private boolean skipBlankRecords;
        private XlsxCellValues cellValues;
        private Locale locale;

        private Builder() {
        }

        public Builder sheet(String sheet) {
            this.sheet = sheet;
            return this;
        }

        public Builder hasHeader(boolean hasHeader) {
            this.hasHeader = hasHeader;
            return this;
        }

        public Builder firstDataRow(int firstDataRow) {
            this.firstDataRow = firstDataRow;
            return this;
        }

        public Builder skipBlankRecords(boolean skipBlankRecords) {
            this.skipBlankRecords = skipBlankRecords;
            return this;
        }

        public Builder cellValues(XlsxCellValues cellValues) {
            this.cellValues = cellValues;
            return this;
        }

        public Builder locale(Locale locale) {
            this.locale = locale;
            return this;
        }

        public XlsxOptions build() {
            return new XlsxOptions(sheet, hasHeader, firstDataRow, skipBlankRecords, cellValues, locale);
        }
    }
}
