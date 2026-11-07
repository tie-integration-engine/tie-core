package org.tie.internal.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.tie.FileErrorType;
import org.tie.Warning;
import org.tie.config.CsvOptions;
import org.tie.config.FileFormat;
import org.tie.config.Layout;
import org.tie.config.XlsxOptions;
import org.tie.internal.format.tabular.CsvSource;
import org.tie.internal.format.tabular.TabularRecordReader;
import org.tie.internal.format.tabular.XlsxSource;
import org.tie.internal.format.tree.JsonRecordReader;
import org.tie.internal.format.tree.XmlRecordReader;

/** Opens the {@link RecordReader} of a file for a root layout and its child layouts. */
public final class RecordReaders {

    private RecordReaders() {
    }

    /** Opens a local file; XLSX needs a file because it is read with random access (never in memory). */
    public static RecordReader open(FileFormat format, Path file, Layout root, List<Layout> layouts,
                                    Consumer<Warning> warnings, String fileName) {
        if (format == FileFormat.XLSX) {
            XlsxOptions options = root.formatOptions().xlsx();
            return new TabularRecordReader(new XlsxSource(file, options), root, options.hasHeader(),
                    options.firstDataRow(), false, warnings, fileName);
        }
        InputStream in;
        try {
            in = Files.newInputStream(file);
        } catch (IOException e) {
            throw new FileFailure(FileErrorType.CHANNEL, "cannot open " + fileName + ": " + e.getMessage(), null, e);
        }
        return open(format, in, root, layouts, warnings, fileName);
    }

    /**
     * Opens a stream of a text format (CSV, JSON, XML). The reader closes the stream.
     *
     * @throws IllegalArgumentException for XLSX, which must be opened from a file
     */
    public static RecordReader open(FileFormat format, InputStream in, Layout root, List<Layout> layouts,
                                    Consumer<Warning> warnings, String fileName) {
        try {
            RecordReader reader = switch (format) {
                case CSV -> {
                    CsvOptions options = root.formatOptions().csv();
                    yield new TabularRecordReader(new CsvSource(in, options), root, options.hasHeader(),
                            options.firstDataRow(), options.skipBlankRecords(), warnings, fileName);
                }
                case JSON -> new JsonRecordReader(in, root.formatOptions().json(), root, layouts);
                case XML -> new XmlRecordReader(in, root.formatOptions().xml(), root, layouts);
                case XLSX -> throw new IllegalArgumentException("XLSX must be read from a file");
            };
            return new ClosingReader(reader, in);
        } catch (RuntimeException e) {
            closeQuietly(in);
            throw e;
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // already failing
        }
    }

    /** Closes the input stream together with the reader. */
    private record ClosingReader(RecordReader reader, InputStream in) implements RecordReader {

        @Override
        public ReadRecord next() {
            return reader.next();
        }

        @Override
        public void close() {
            try (in) {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
