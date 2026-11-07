package org.tie.internal.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.tie.FileResult;
import org.tie.ImportContext;
import org.tie.ImportResult;
import org.tie.LineRejection;
import org.tie.ReceivedFile;
import org.tie.Tie;
import org.tie.TypedRecord;
import org.tie.Warning;
import org.tie.config.ColumnLocation;
import org.tie.config.CsvOptions;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.spi.FileHook;
import org.tie.spi.Outcome;
import org.tie.spi.RecordConsumer;
import org.tie.spi.RunListener;

/** A local-directory import with a recording consumer, listener and hook. */
final class ImportHarness {

    static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    final Path in;
    final Path work;
    final List<String> events = new ArrayList<>();
    final List<TypedRecord> records = new ArrayList<>();
    final List<LineRejection> rejections = new ArrayList<>();
    final List<FileResult> finished = new ArrayList<>();
    final List<Warning> warnings = new ArrayList<>();
    Function<TypedRecord, Outcome> behaviour = record -> Outcome.APPLIED;
    Tie.Builder tie = Tie.builder().clock(Clock.fixed(NOW, ZoneOffset.UTC));
    ImportContext.Builder context;

    final RecordConsumer consumer = record -> {
        events.add("consumer " + record.layout() + ":" + record.lineNumber());
        records.add(record);
        return behaviour.apply(record);
    };

    final RunListener listener = new RunListener() {
        @Override
        public void onFileStarted(String fileName, String layout) {
            events.add("started " + fileName);
        }

        @Override
        public void onLineRejected(LineRejection rejection) {
            events.add("rejected " + rejection.lineNumber());
            rejections.add(rejection);
        }

        @Override
        public void onFileFinished(FileResult result) {
            events.add("finished " + result.name() + " " + result.status());
            finished.add(result);
        }

        @Override
        public void onWarning(Warning warning) {
            warnings.add(warning);
        }
    };

    final List<ReceivedFile> received = new ArrayList<>();
    final List<FileResult> completed = new ArrayList<>();

    final FileHook hook = new FileHook() {
        @Override
        public void onFileReceived(ReceivedFile file) {
            events.add("received " + file.name());
            received.add(file);
        }

        @Override
        public void onFileCompleted(FileResult partial) {
            events.add("completed " + partial.name());
            completed.add(partial);
        }
    };

    ImportHarness(Path root) {
        try {
            this.in = Files.createDirectories(root.resolve("in"));
            this.work = Files.createDirectories(root.resolve("work"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.context = ImportContext.builder(consumer).listener(listener).workingDirectory(work);
    }

    Path write(String name, String content) {
        try {
            return Files.writeString(in.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A CSV file of {@code count} records with header {@code value}: v1, v2… */
    Path writeLines(String name, int count) {
        StringBuilder content = new StringBuilder("value\n");
        for (int i = 1; i <= count; i++) {
            content.append('v').append(i).append('\n');
        }
        return write(name, content.toString());
    }

    ImportResult run(IntegrationDefinition.Builder definition) {
        return tie.build().importFiles(definition.build(), context.build());
    }

    List<String> filesIn(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- definitions

    static Layout.Builder csv(String name, String pattern) {
        return Layout.builder(name).fileNamePattern(pattern).allowedFormats(FileFormat.CSV)
                .csv(CsvOptions.builder().separator(';').build()).columnLocation(ColumnLocation.HEADER)
                .field(FieldDefinition.builder("value"));
    }

    IntegrationDefinition.Builder importOf(Layout.Builder... layouts) {
        IntegrationDefinition.Builder definition = IntegrationDefinition.importing("test")
                .inputChannel(LocalDirectoryChannel.of(in));
        for (Layout.Builder layout : layouts) {
            definition.layout(layout);
        }
        return definition;
    }

    /** The usual single-layout import: {@code alta_<yyyyMMddHHmmss>.csv} with a {@code value} column. */
    IntegrationDefinition.Builder altas() {
        return importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}"));
    }
}
