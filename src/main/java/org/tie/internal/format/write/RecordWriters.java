package org.tie.internal.format.write;

import java.io.OutputStream;
import org.tie.config.FileFormat;
import org.tie.config.Layout;

/** Opens the {@link RecordWriter} of an export for its root layout. */
public final class RecordWriters {

    private RecordWriters() {
    }

    /** The writer owns {@code out} and closes it. */
    public static RecordWriter open(FileFormat format, OutputStream out, Layout root) {
        return switch (format) {
            case CSV -> new CsvRecordWriter(out, root);
            case XLSX -> new XlsxRecordWriter(out, root);
            case JSON -> new JsonRecordWriter(out, root);
            case XML -> new XmlRecordWriter(out, root);
        };
    }
}
