package org.tie.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** CSV reading and writing options (§4.1). */
public record CsvOptions(char separator, char quote, Charset encoding, boolean hasHeader, int firstDataRow,
                         boolean skipBlankRecords) {

    public static final CsvOptions DEFAULTS = builder().build();

    public CsvOptions {
        encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().separator(separator).quote(quote).encoding(encoding).hasHeader(hasHeader)
                .firstDataRow(firstDataRow).skipBlankRecords(skipBlankRecords);
    }

    public static final class Builder {
        private char separator = ',';
        private char quote = '"';
        private Charset encoding = StandardCharsets.UTF_8;
        private boolean hasHeader = true;
        private int firstDataRow = 2;
        private boolean skipBlankRecords;

        private Builder() {
        }

        public Builder separator(char separator) {
            this.separator = separator;
            return this;
        }

        public Builder quote(char quote) {
            this.quote = quote;
            return this;
        }

        public Builder encoding(Charset encoding) {
            this.encoding = encoding;
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

        public CsvOptions build() {
            return new CsvOptions(separator, quote, encoding, hasHeader, firstDataRow, skipBlankRecords);
        }
    }
}
