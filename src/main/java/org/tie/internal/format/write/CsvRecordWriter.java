package org.tie.internal.format.write;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.tie.config.CsvOptions;
import org.tie.config.Layout;
import org.tie.internal.engine.Values;

/** CSV export: header (if {@code hasHeader}) and one row per record; typed values in canonical text. */
final class CsvRecordWriter implements RecordWriter {

    private final CSVPrinter printer;
    private final Columns columns;

    CsvRecordWriter(OutputStream out, Layout layout) {
        CsvOptions options = layout.formatOptions().csv();
        this.columns = Columns.of(layout);
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(options.separator()).setQuote(options.quote())
                .setRecordSeparator("\r\n").get();
        try {
            this.printer = new CSVPrinter(new OutputStreamWriter(out, options.encoding()), format);
            if (options.hasHeader()) {
                printer.printRecord(columns.header());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(OutputRecord record) {
        List<String> row = new ArrayList<>();
        for (String field : columns.fields()) {
            row.add(field == null ? "" : Values.canonical(record.values().get(field)));
        }
        try {
            printer.printRecord(row);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void finish() {
        try {
            printer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            printer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
