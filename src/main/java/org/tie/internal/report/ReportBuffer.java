package org.tie.internal.report;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lines of one report (file and layout), kept as JSON lines in the working directory while the
 * file is read, so memory does not grow with the rejections. It also collects the raw value keys in
 * order of appearance: the columns of a tabular report.
 */
public final class ReportBuffer implements AutoCloseable {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Path file;
    private final BufferedWriter writer;
    private final Set<String> keys = new LinkedHashSet<>();
    private int count;

    public ReportBuffer(Path workingDirectory, String name) throws IOException {
        this.file = Files.createTempFile(workingDirectory, "tie-report-", "-" + name + ".jsonl");
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    public void add(ReportLine line) {
        keys.addAll(line.values().keySet());
        try {
            writer.write(MAPPER.writeValueAsString(line));
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        count++;
    }

    public int count() {
        return count;
    }

    public Set<String> keys() {
        return keys;
    }

    /** The lines in the order they were added; call once, after the last {@link #add}. */
    public Iterator<ReportLine> lines() throws IOException {
        writer.flush();
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        return new Iterator<>() {
            private String next = advance();

            private String advance() {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        reader.close();
                    }
                    return line;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public ReportLine next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                ReportLine line = MAPPER.readValue(next, ReportLine.class);
                next = advance();
                return line;
            }
        };
    }

    @Override
    public void close() throws IOException {
        writer.close();
        Files.deleteIfExists(file);
    }
}
