package org.tie.internal.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tie.ConfigurationException;
import org.tie.ExportContext;
import org.tie.ExportResult;
import org.tie.FileError;
import org.tie.FileErrorType;
import org.tie.LineError;
import org.tie.LineRejection;
import org.tie.ResultStatus;
import org.tie.Warning;
import org.tie.WarningCode;
import org.tie.config.ChannelDefinition;
import org.tie.config.Direction;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnExistingFile;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.SftpChannel;
import org.tie.internal.Exceptions;
import org.tie.internal.channel.ChannelException;
import org.tie.internal.delivery.Delivered;
import org.tie.internal.delivery.OutputChannel;
import org.tie.internal.engine.ExportEngine;
import org.tie.internal.engine.Values;
import org.tie.internal.format.write.OutputRecord;
import org.tie.internal.format.write.RecordWriter;
import org.tie.internal.format.write.RecordWriters;
import org.tie.internal.pattern.NamePattern;
import org.tie.internal.pattern.NamePatternException;
import org.tie.internal.validation.DefinitionValidator;
import org.tie.internal.validation.SpiValidator;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.FieldTransformer;
import org.tie.spi.RunListener;
import org.tie.spi.SecretProvider;

/**
 * One execution of {@code Tie.exportRecords} (§3 "Happy path — exportación"): the records are written
 * in streaming to a file in the working directory, which is delivered at the end only if the export
 * neither exceeded the threshold nor was cancelled (A11, A15).
 */
public final class ExportRun {

    private static final Logger LOG = LoggerFactory.getLogger(ExportRun.class);
    private static final RunListener NO_LISTENER = new RunListener() {
    };

    private final IntegrationDefinition definition;
    private final Iterator<Map<String, Object>> records;
    private final ExportContext context;
    private final Map<String, FieldTransformer> transformers;
    private final DefaultValueResolver resolver;
    private final SecretProvider secrets;
    private final Clock clock;
    private final RunListener listener;
    private final List<LineRejection> rejections = new ArrayList<>();
    private final List<Warning> warnings = new ArrayList<>();
    private final Map<String, ExportEngine> engines = new LinkedHashMap<>();
    private String fileName;
    private int line;
    private int written;

    public ExportRun(IntegrationDefinition definition, Iterator<Map<String, Object>> records, ExportContext context,
                     Map<String, FieldTransformer> transformers, DefaultValueResolver resolver,
                     SecretProvider secrets, Clock clock) {
        this.definition = definition;
        this.records = records;
        this.context = context;
        this.transformers = transformers;
        this.resolver = resolver;
        this.secrets = secrets;
        this.clock = clock;
        this.listener = context == null || context.listener() == null ? NO_LISTENER : context.listener();
    }

    public ExportResult run() {
        validate();
        Layout root = definition.layouts().stream().filter(Layout::isRoot).findFirst().orElseThrow();
        FileFormat format = root.allowedFormats().iterator().next();
        definition.layouts().forEach(layout -> engines.put(layout.name(), new ExportEngine(layout, transformers, resolver)));
        fileName = NamePattern.parse(definition.outputNamePattern())
                .render(context.variables(), LocalDateTime.now(clock)) + "." + format.extension();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(context.workingDirectory(), "tie-", "-" + fileName);
            try (RecordWriter writer = RecordWriters.open(format, Files.newOutputStream(temporary), root)) {
                while (records.hasNext()) {
                    if (context.cancellation() != null && context.cancellation().isCancellationRequested()) {
                        LOG.info("export {}: cancelled", definition.id());
                        return result(ResultStatus.CANCELLED, null, null);
                    }
                    OutputRecord record = record(records.next(), root, null);
                    if (record != null) {
                        writer.write(record);
                    }
                }
                writer.finish();
            }
            OutputChannel channel = OutputChannels.open(definition.outputChannel(), clock, context.providedOutput(), secrets);
            try (channel) {
                Delivered delivered = channel.deliver(temporary, fileName, onExistingFile(definition.outputChannel()),
                        this::warn);
                LOG.info("export {}: delivered {} to {}", definition.id(), delivered.fileName(), delivered.destination());
                return result(ResultStatus.SUCCESS, delivered, null);
            }
        } catch (ExportFailure failure) {
            return result(ResultStatus.FAILED, null, failure.error);
        } catch (ChannelException e) {
            return result(ResultStatus.FAILED, null, e.toFileError());
        } catch (IOException | UncheckedIOException e) {
            return result(ResultStatus.FAILED, null, FileError.of(FileErrorType.CHANNEL,
                    "cannot write " + fileName + " to the working directory: " + Exceptions.message(e)));
        } catch (IllegalStateException e) {
            return result(ResultStatus.FAILED, null, FileError.of(FileErrorType.STRUCTURAL,
                    "cannot write " + fileName + ": " + Exceptions.message(e)));
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    warn(new Warning(WarningCode.TEMP_CLEANUP_FAILED, "cannot delete working copy " + temporary, fileName));
                }
            }
        }
    }

    /** Runs a record and its children through the engines; null if the record is rejected (A11). */
    private OutputRecord record(Map<String, Object> values, Layout layout, Integer parentLine) {
        int lineNumber = ++line;
        if (values == null) {
            throw new ExportFailure(FileError.of(FileErrorType.STRUCTURAL, "record " + lineNumber + " is null"));
        }
        ExportEngine.Result result = engines.get(layout.name()).process(values);
        if (result instanceof ExportEngine.Rejected(List<LineError> errors)) {
            reject(values, layout, lineNumber, parentLine, errors);
            return null;
        }
        List<OutputRecord.Children> children = new ArrayList<>();
        for (Layout child : definition.layouts()) {
            if (!layout.name().equals(child.parent())) {
                continue;
            }
            Object nested = values.get(child.nestedKey());
            List<OutputRecord> childRecords = new ArrayList<>();
            if (nested instanceof List<?> list) {
                for (Object element : list) {
                    if (!(element instanceof Map<?, ?>)) {
                        throw notRecords(lineNumber, child);
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> childValues = (Map<String, Object>) element;
                    OutputRecord childRecord = record(childValues, child, lineNumber);
                    if (childRecord != null) {
                        childRecords.add(childRecord);
                    }
                }
            } else if (nested != null) {
                throw notRecords(lineNumber, child);
            }
            children.add(new OutputRecord.Children(child, childRecords));
        }
        written++;
        return new OutputRecord(((ExportEngine.Written) result).values(), children);
    }

    private void reject(Map<String, Object> values, Layout layout, int lineNumber, Integer parentLine,
                        List<LineError> errors) {
        Map<String, String> raw = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && !(value instanceof List<?>)) {
                raw.put(key, Values.canonical(value));
            }
        });
        LineRejection rejection = new LineRejection(lineNumber, null, layout.name(), errors, fileName, parentLine, raw);
        rejections.add(rejection);
        try {
            listener.onLineRejected(rejection);
        } catch (RuntimeException e) {
            warn(new Warning(WarningCode.LISTENER_FAILED, "onLineRejected failed: " + Exceptions.message(e), fileName));
        }
        Integer max = definition.rejectionThreshold().maxRejected();
        if (max != null && rejections.size() > max) {
            throw new ExportFailure(FileError.of(FileErrorType.THRESHOLD, "rejection threshold exceeded: "
                    + rejections.size() + " rejected records, at most " + max + " allowed"));
        }
    }

    private static ExportFailure notRecords(int lineNumber, Layout child) {
        return new ExportFailure(FileError.of(FileErrorType.STRUCTURAL, "record " + lineNumber + ": '"
                + child.nestedKey() + "' must be a list of records for layout '" + child.name() + "'"));
    }

    private ExportResult result(ResultStatus status, Delivered delivered, FileError error) {
        return new ExportResult(status, written, rejections, delivered == null ? null : delivered.fileName(),
                delivered == null ? null : delivered.destination(), error, warnings);
    }

    private void warn(Warning warning) {
        warnings.add(warning);
        try {
            listener.onWarning(warning);
        } catch (RuntimeException ignored) {
            // a listener problem is not a reason to fail the export
        }
    }

    private static OnExistingFile onExistingFile(ChannelDefinition channel) {
        return switch (channel) {
            case LocalDirectoryChannel local -> local.onExistingFile();
            case SftpChannel sftp -> sftp.onExistingFile();
            case ProvidedStreamChannel ignored -> OnExistingFile.OVERWRITE;
        };
    }

    // ---------------------------------------------------------------- validation (A1)

    private void validate() {
        if (definition == null) {
            throw new ConfigurationException("definition is required");
        }
        List<String> problems = new ArrayList<>(DefinitionValidator.validate(definition));
        problems.addAll(SpiValidator.validate(definition, transformers.keySet(), secrets != null));
        if (definition.direction() != null && definition.direction() != Direction.EXPORT) {
            problems.add("exportRecords requires an EXPORT definition");
        }
        if (records == null) {
            problems.add("records are required");
        }
        if (context == null) {
            problems.add("context is required");
            throw new ConfigurationException(problems);
        }
        if (definition.outputNamePattern() != null) {
            try {
                NamePattern.parse(definition.outputNamePattern()).variables().stream()
                        .filter(name -> !context.variables().containsKey(name))
                        .forEach(name -> problems.add("variable '" + name + "' of outputNamePattern is not in the context"));
            } catch (NamePatternException ignored) {
                // reported by the definition validator
            }
        }
        if (definition.outputChannel() instanceof ProvidedStreamChannel && context.providedOutput() == null) {
            problems.add("a ProvidedStreamChannel output requires providedOutput in the context");
        }
        if (!Files.isDirectory(context.workingDirectory())) {
            problems.add("workingDirectory " + context.workingDirectory() + " does not exist");
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
    }

    /** Fails the export without delivering anything. */
    private static final class ExportFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient FileError error;

        ExportFailure(FileError error) {
            super(error.message(), null, false, false);
            this.error = error;
        }
    }
}
