package org.tie.internal.format;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tie.Warning;
import org.tie.config.FileFormat;
import org.tie.config.Layout;
import org.tie.internal.engine.FieldValues;
import org.tie.internal.engine.ImportEngine;
import org.tie.internal.engine.LineResult;

/** Reads a whole test file and keeps its records and warnings. */
public final class Reading {

    public final List<ReadRecord> records = new ArrayList<>();
    public final List<Warning> warnings = new ArrayList<>();

    private final Layout root;

    private Reading(Layout root) {
        this.root = root;
    }

    public static Reading text(FileFormat format, String content, Layout root, Layout... children) {
        return bytes(format, content.getBytes(StandardCharsets.UTF_8), root, children);
    }

    public static Reading text(FileFormat format, String content, Charset charset, Layout root, Layout... children) {
        return bytes(format, content.getBytes(charset), root, children);
    }

    public static Reading bytes(FileFormat format, byte[] content, Layout root, Layout... children) {
        Reading reading = new Reading(root);
        try (RecordReader reader = RecordReaders.open(format, new ByteArrayInputStream(content), root,
                layouts(root, children), reading.warnings::add, "test." + format.extension())) {
            reading.readAll(reader);
        }
        return reading;
    }

    public static Reading file(FileFormat format, Path file, Layout root, Layout... children) {
        Reading reading = new Reading(root);
        try (RecordReader reader = RecordReaders.open(format, file, root, layouts(root, children),
                reading.warnings::add, file.getFileName().toString())) {
            reading.readAll(reader);
        }
        return reading;
    }

    private void readAll(RecordReader reader) {
        ReadRecord record;
        while ((record = reader.next()) != null) {
            records.add(record);
        }
    }

    private static List<Layout> layouts(Layout root, Layout... children) {
        List<Layout> layouts = new ArrayList<>(List.of(root));
        layouts.addAll(List.of(children));
        return layouts;
    }

    public List<Map<String, String>> raw() {
        return records.stream().map(record -> record.input().rawValues()).toList();
    }

    public List<Map<String, String>> inputs() {
        return records.stream().map(record -> record.input().fieldInputs()).toList();
    }

    public List<Integer> rows() {
        return records.stream().map(ReadRecord::rowNumber).toList();
    }

    /** The field engine's result for every record, accepted or rejected. */
    public List<LineResult> results() {
        ImportEngine engine = new ImportEngine(root, Map.of(), null);
        return records.stream().map(record -> engine.process(record.input())).toList();
    }

    /** Typed values of every record through the field engine; a rejected line fails the test. */
    public List<Map<String, Object>> typed() {
        ImportEngine engine = new ImportEngine(root, Map.of(), null);
        return records.stream().map(record -> {
            LineResult result = engine.process(record.input());
            if (!(result instanceof LineResult.Accepted(FieldValues values))) {
                throw new AssertionError("rejected: " + result);
            }
            return values.asMap();
        }).toList();
    }
}
