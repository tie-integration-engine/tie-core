package org.tie.internal.format.tabular;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.tie.FileErrorType;
import org.tie.config.CsvOptions;
import org.tie.internal.format.DecodingReader;
import org.tie.internal.format.FileFailure;

/**
 * CSV rows with Commons CSV. Every parser record is a row, empty lines included; a quoted value with
 * line breaks does not open new rows (§4.1).
 */
public final class CsvSource implements TabularSource {

    private final CSVParser parser;
    private final Iterator<CSVRecord> records;

    public CsvSource(InputStream in, CsvOptions options) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(options.separator())
                .setQuote(options.quote())
                .setIgnoreEmptyLines(false)
                .setIgnoreSurroundingSpaces(false)
                .setTrim(false)
                .get();
        try {
            this.parser = format.parse(new DecodingReader(in, options.encoding()));
        } catch (IOException e) {
            throw failure(e, 1);
        }
        this.records = parser.iterator();
    }

    @Override
    public TabularRow nextRow() {
        int row = (int) parser.getRecordNumber() + 1;
        try {
            if (!records.hasNext()) {
                return null;
            }
            CSVRecord record = records.next();
            List<String> cells = new ArrayList<>(record.size());
            record.forEach(cells::add);
            boolean blank = cells.size() == 1 && cells.getFirst().isEmpty();
            return new TabularRow((int) record.getRecordNumber(), blank ? List.of() : cells, blank);
        } catch (UncheckedIOException e) {
            throw failure(e.getCause(), row);
        } catch (IllegalStateException e) {
            throw failure(e.getCause() instanceof IOException io ? io : new IOException(e.getMessage(), e), row);
        }
    }

    private static FileFailure failure(IOException e, int row) {
        if (e instanceof DecodingReader.EncodingException encoding) {
            return new FileFailure(FileErrorType.ENCODING, encoding.getMessage(), encoding.line(), encoding);
        }
        return new FileFailure(FileErrorType.STRUCTURAL, "invalid CSV at row " + row + ": " + e.getMessage(), row, e);
    }

    @Override
    public boolean canonicalValues() {
        return false;
    }

    @Override
    public void close() {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
