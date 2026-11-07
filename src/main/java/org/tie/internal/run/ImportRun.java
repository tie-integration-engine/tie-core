package org.tie.internal.run;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tie.ConfigurationException;
import org.tie.FileError;
import org.tie.FileErrorType;
import org.tie.FileResult;
import org.tie.FileStatus;
import org.tie.ImportContext;
import org.tie.ImportResult;
import org.tie.LineRejection;
import org.tie.ProvidedInput;
import org.tie.ReceivedFile;
import org.tie.RemoteFile;
import org.tie.ResultStatus;
import org.tie.ReportRef;
import org.tie.config.OnExistingFile;
import org.tie.config.ReportMode;
import org.tie.config.ResultReport;
import org.tie.internal.delivery.Delivered;
import org.tie.internal.delivery.OutputChannel;
import org.tie.internal.report.ReportBuffer;
import org.tie.internal.report.ReportLine;
import org.tie.internal.report.ReportWriter;
import org.tie.Warning;
import org.tie.WarningCode;
import org.tie.config.ChannelDefinition;
import org.tie.config.DeliveryStrategy;
import org.tie.config.Direction;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.PostSuccessAction;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.SftpChannel;
import org.tie.internal.Exceptions;
import org.tie.internal.channel.ChannelException;
import org.tie.internal.channel.FileNames;
import org.tie.internal.channel.InputChannel;
import org.tie.internal.channel.LocalCopy;
import org.tie.internal.channel.LocalDirectoryInput;
import org.tie.internal.engine.ImportEngine;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.RecordReader;
import org.tie.internal.format.RecordReaders;
import org.tie.internal.pattern.NamePattern;
import org.tie.internal.pattern.NamePatternException;
import org.tie.internal.validation.DefinitionValidator;
import org.tie.internal.validation.SpiValidator;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.FieldTransformer;
import org.tie.spi.RunListener;
import org.tie.spi.SecretProvider;
import org.tie.internal.channel.sftp.SftpInput;

/**
 * One execution of {@code Tie.importFiles} (§3): validates everything before any I/O (INV-3), lists
 * every input channel, selects and orders the candidates, and processes them one by one until the end,
 * a failure (A7) or a cancellation (A8).
 */
public final class ImportRun {

    private static final Logger LOG = LoggerFactory.getLogger(ImportRun.class);
    private static final Set<String> REPORT_VARIABLES = Set.of("sourceName", "layout");
    private static final RunListener NO_LISTENER = new RunListener() {
    };

    private final IntegrationDefinition definition;
    private final ImportContext context;
    private final Map<String, FieldTransformer> transformers;
    private final DefaultValueResolver resolver;
    private final SecretProvider secrets;
    private final Clock clock;
    private final Path workingDirectory;
    private final RunListener listener;
    private final List<Warning> warnings = new ArrayList<>();
    private final List<ReportRef> reports = new ArrayList<>();
    private Map<String, ImportEngine> engines;

    public ImportRun(IntegrationDefinition definition, ImportContext context, Map<String, FieldTransformer> transformers,
                     DefaultValueResolver resolver, SecretProvider secrets, Clock clock,
                     Path defaultWorkingDirectory) {
        this.definition = definition;
        this.context = context;
        this.transformers = transformers;
        this.resolver = resolver;
        this.secrets = secrets;
        this.clock = clock;
        this.workingDirectory = context == null || context.workingDirectory() == null ? defaultWorkingDirectory
                : context.workingDirectory();
        this.listener = context == null || context.listener() == null ? NO_LISTENER : context.listener();
    }

    public ImportResult run() {
        try {
            validate();
        } catch (ConfigurationException e) {
            closeProvidedStreams();
            throw e;
        }
        engines = new LinkedHashMap<>();
        for (Layout layout : definition.layouts()) {
            engines.put(layout.name(), new ImportEngine(layout, transformers, resolver));
        }
        Map<String, LocalDateTime> watermarks = new LinkedHashMap<>(context.watermarks());
        List<InputChannel> channels = new ArrayList<>();
        try {
            List<Candidate> candidates = new ArrayList<>();
            FileError selectionError = select(channels, candidates, watermarks);
            if (selectionError != null) {
                LOG.info("import {}: failed before processing any file: {}", definition.id(), selectionError.message());
                return new ImportResult(ResultStatus.FAILED, List.of(), watermarks, warnings, List.of(), selectionError);
            }
            List<FileResult> files = new ArrayList<>();
            ResultStatus status = ResultStatus.SUCCESS;
            for (Candidate candidate : candidates) {
                if (status == ResultStatus.SUCCESS && isCancelled()) {
                    status = ResultStatus.CANCELLED;
                }
                if (status != ResultStatus.SUCCESS) {
                    files.add(notProcessed(candidate));
                    continue;
                }
                FileResult result = new FileRun(candidate).run();
                files.add(result);
                LOG.info("import {}: file {} {}", definition.id(), candidate.name(), result.status());
                switch (result.status()) {
                    case FAILED -> status = ResultStatus.FAILED;
                    case CANCELLED -> status = ResultStatus.CANCELLED;
                    case SUCCESS -> advanceWatermark(candidate, watermarks);
                    case NOT_PROCESSED -> {
                    }
                }
            }
            return new ImportResult(status, files, watermarks, warnings, reports, null);
        } finally {
            channels.forEach(InputChannel::close);
            closeProvidedStreams();
        }
    }

    // ---------------------------------------------------------------- validation (A1, INV-3)

    private void validate() {
        if (definition == null) {
            throw new ConfigurationException("definition is required");
        }
        List<String> problems = new ArrayList<>(DefinitionValidator.validate(definition));
        problems.addAll(SpiValidator.validate(definition, transformers.keySet(), secrets != null));
        if (definition.direction() != null && definition.direction() != Direction.IMPORT) {
            problems.add("importFiles requires an IMPORT definition");
        }
        if (context == null) {
            problems.add("context is required");
            throw new ConfigurationException(problems);
        }
        if (context.consumer() == null) {
            problems.add("consumer is required");
        }
        Set<String> missing = new LinkedHashSet<>();
        for (Layout layout : definition.layouts()) {
            if (layout.isRoot() && layout.fileNamePattern() != null) {
                pattern(layout).ifPresent(pattern -> pattern.variables().stream()
                        .filter(name -> !context.variables().containsKey(name))
                        .forEach(name -> missing.add("layout '" + layout.name() + "': variable '" + name
                                + "' of fileNamePattern is not in the context")));
            }
        }
        problems.addAll(missing);
        boolean providedChannel = definition.inputChannels().stream().anyMatch(ProvidedStreamChannel.class::isInstance);
        if (!context.providedInputs().isEmpty() && !providedChannel) {
            problems.add("providedInputs require a ProvidedStreamChannel input channel");
        }
        List<ProvidedInput> provided = context.providedInputs();
        for (int i = 0; i < provided.size(); i++) {
            ProvidedInput input = provided.get(i);
            Optional<Layout> layout = definition.layout(input.layout());
            if (layout.isEmpty()) {
                problems.add("providedInputs[" + i + "]: layout '" + input.layout() + "' does not exist");
            } else if (!layout.get().isRoot()) {
                problems.add("providedInputs[" + i + "]: layout '" + input.layout() + "' is not a root layout");
            }
            if (input.fileName() == null || !FileNames.isSafe(input.fileName())) {
                problems.add("providedInputs[" + i + "]: fileName '" + input.fileName() + "' is not a safe file name");
            }
        }
        if (!Files.isDirectory(workingDirectory)) {
            problems.add("workingDirectory " + workingDirectory + " does not exist");
        }
        ResultReport report = definition.resultReport();
        if (report.mode() != ReportMode.NONE && report.namePattern() != null) {
            try {
                NamePattern.parse(report.namePattern()).variables().stream()
                        .filter(name -> !REPORT_VARIABLES.contains(name) && !context.variables().containsKey(name))
                        .forEach(name -> problems.add("resultReport: variable '" + name
                                + "' of namePattern is not in the context"));
            } catch (NamePatternException ignored) {
                // reported by the definition validator
            }
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
    }

    private static Optional<NamePattern> pattern(Layout layout) {
        try {
            return Optional.of(NamePattern.parse(layout.fileNamePattern()));
        } catch (NamePatternException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- selection (§3 steps 3-4, A14)

    private FileError select(List<InputChannel> channels, List<Candidate> candidates,
                             Map<String, LocalDateTime> watermarks) {
        List<Layout> roots = definition.layouts().stream().filter(Layout::isRoot).toList();
        Map<String, NamePattern> patterns = new LinkedHashMap<>();
        roots.forEach(root -> patterns.put(root.name(), NamePattern.parse(root.fileNamePattern())));
        boolean watermark = definition.duplicateProtection().watermark();
        List<Candidate> listed = new ArrayList<>();
        for (ChannelDefinition definitionChannel : definition.inputChannels()) {
            if (definitionChannel instanceof ProvidedStreamChannel) {
                continue;
            }
            InputChannel channel = open(definitionChannel);
            channels.add(channel);
            List<RemoteFile> files;
            try {
                files = channel.list();
            } catch (ChannelException e) {
                return e.toFileError();
            }
            for (RemoteFile file : files) {
                if (!FileNames.isSafe(file.name())) {
                    warn(new Warning(WarningCode.UNSAFE_FILE_NAME, "file name '" + file.name()
                            + "' in " + channel.description() + " is not safe and is ignored", file.name()), null);
                    continue;
                }
                String base = FileNames.baseName(file.name());
                List<Layout> matches = new ArrayList<>();
                LocalDateTime timestamp = null;
                for (Layout root : roots) {
                    Optional<NamePattern.Match> match = patterns.get(root.name()).match(base, context.variables());
                    if (match.isPresent()) {
                        matches.add(root);
                        timestamp = match.get().timestamp();
                    }
                }
                if (matches.isEmpty()) {
                    continue;
                }
                if (matches.size() > 1) {
                    return FileError.of(FileErrorType.AMBIGUOUS_LAYOUT, "file '" + file.name() + "' matches layouts "
                            + matches.stream().map(Layout::name).collect(Collectors.joining(", ")));
                }
                Layout layout = matches.getFirst();
                LocalDateTime mark = watermarks.get(layout.name());
                if (watermark && timestamp != null && mark != null && !timestamp.isAfter(mark)) {
                    continue;
                }
                if (context.alreadyProcessed() != null && context.alreadyProcessed().test(file)) {
                    continue;
                }
                listed.add(Candidate.ofChannel(file, layout, timestamp, channel, definitionChannel));
            }
        }
        Map<String, List<Candidate>> sameTimestamp = new LinkedHashMap<>();
        for (Candidate candidate : listed) {
            if (candidate.timestamp() != null) {
                sameTimestamp.computeIfAbsent(candidate.layout().name() + "\u0000" + candidate.timestamp(),
                        key -> new ArrayList<>()).add(candidate);
            }
        }
        for (List<Candidate> group : sameTimestamp.values()) {
            if (group.size() > 1) {
                return FileError.of(FileErrorType.DUPLICATE_TIMESTAMP, "files "
                        + group.stream().map(Candidate::name).collect(Collectors.joining(", ")) + " of layout '"
                        + group.getFirst().layout().name() + "' have the same timestamp " + group.getFirst().timestamp());
            }
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        roots.forEach(root -> order.put(root.name(), order.size()));
        listed.sort(Comparator.comparingInt((Candidate c) -> order.get(c.layout().name()))
                .thenComparing(Candidate::timestamp, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Candidate::name));
        candidates.addAll(listed);
        for (ProvidedInput input : context.providedInputs()) {
            candidates.add(Candidate.ofProvided(input, definition.layout(input.layout()).orElseThrow()));
        }
        return null;
    }

    private InputChannel open(ChannelDefinition channel) {
        return switch (channel) {
            case LocalDirectoryChannel local -> new LocalDirectoryInput(local);
            case SftpChannel sftp -> new SftpInput(sftp, secrets);
            case ProvidedStreamChannel ignored -> throw new IllegalStateException("provided streams are not listed");
        };
    }

    // ---------------------------------------------------------------- one file

    /** Processes one candidate file (§3 steps 5-6, A3-A13). */
    private final class FileRun {

        private final Candidate candidate;
        private final List<Warning> fileWarnings = new ArrayList<>();
        private final List<Path> temporaries = new ArrayList<>();
        private final Map<String, ReportBuffer> reportBuffers = new LinkedHashMap<>();
        private RecordDelivery delivery;

        FileRun(Candidate candidate) {
            this.candidate = candidate;
        }

        FileResult run() {
            FileResult result;
            safely(() -> listener.onFileStarted(candidate.name(), candidate.layout().name()));
            try {
                result = process();
            } catch (FileFailure failure) {
                result = result(FileStatus.FAILED, failure.toFileError(delivery == null ? null : delivery.nextLine()));
            } catch (ChannelException e) {
                result = result(FileStatus.FAILED, e.toFileError());
            } catch (RecordDelivery.Cancelled e) {
                result = result(FileStatus.CANCELLED, null);
            } finally {
                cleanUp();
            }
            FileResult finished = result;
            try {
                listener.onFileFinished(finished);
            } catch (RuntimeException e) {
                warn(new Warning(WarningCode.LISTENER_FAILED, "onFileFinished failed for " + candidate.name() + ": "
                        + Exceptions.message(e), candidate.name()), null);
            }
            return result;
        }

        private FileResult process() throws ChannelException {
            Layout layout = candidate.layout();
            FileFormat format = format();
            Long maxFileSize = maxFileSize();
            if (candidate.remote() != null && maxFileSize != null && candidate.remote().size() > maxFileSize) {
                throw new FileFailure(FileErrorType.FILE_TOO_LARGE, "file too large: " + candidate.remote().size()
                        + " bytes, at most " + maxFileSize + " allowed");
            }
            boolean validateFirst = definition.deliveryStrategy() == DeliveryStrategy.VALIDATE_THEN_DELIVER;
            Path file = null;
            if (candidate.remote() != null) {
                LocalCopy copy = candidate.channel().fetch(candidate.remote(), workingDirectory);
                if (copy.temporary()) {
                    temporaries.add(copy.path());
                }
                file = copy.path();
                hook(() -> context.fileHook().onFileReceived(new ReceivedFile(candidate.name(), layout.name(),
                        candidate.timestamp(), candidate.remote().size(), copy.path())), "onFileReceived");
                if (validateFirst && !copy.temporary()) {
                    file = copyToWorkingDirectory(copy.path());
                }
            } else if (validateFirst || format == FileFormat.XLSX) {
                file = spool(candidate.provided().stream());
            }
            if (validateFirst) {
                try (RecordReader reader = reader(format, file)) {
                    RecordDelivery.validate(reader, layout, engines, definition.rejectionThreshold());
                }
            }
            delivery = new RecordDelivery(engines, context.consumer(), definition.consumerErrorPolicy(),
                    definition.rejectionThreshold().maxRejected(), context.cancellation(), candidate.name(),
                    this::rejected, this::applied);
            try (RecordReader reader = file != null ? reader(format, file)
                    : RecordReaders.open(format, candidate.provided().stream(), layout, definition.layouts(),
                    this::readerWarning, candidate.name())) {
                delivery.deliver(reader, layout);
            }
            FileResult partial = result(FileStatus.SUCCESS, null);
            hook(() -> context.fileHook().onFileCompleted(partial), "onFileCompleted");
            if (candidate.remote() != null) {
                postSuccessAction();
            }
            writeReports();
            return result(FileStatus.SUCCESS, null);
        }

        private FileFormat format() {
            String extension = FileNames.extension(candidate.name());
            Optional<FileFormat> format = FileFormat.fromExtension(extension);
            if (format.isEmpty() || !candidate.layout().allowedFormats().contains(format.get())) {
                throw new FileFailure(FileErrorType.EXTENSION_NOT_ALLOWED, "extension not allowed: '" + extension
                        + "' (layout '" + candidate.layout().name() + "' allows " + candidate.layout().allowedFormats()
                        .stream().map(FileFormat::extension).sorted().collect(Collectors.joining(", ")) + ")");
            }
            return format.get();
        }

        private Long maxFileSize() {
            return switch (candidate.channelDefinition()) {
                case LocalDirectoryChannel local -> local.maxFileSize();
                case SftpChannel sftp -> sftp.maxFileSize();
                case null, default -> null;
            };
        }

        private RecordReader reader(FileFormat format, Path file) {
            return RecordReaders.open(format, file, candidate.layout(), definition.layouts(), this::readerWarning,
                    candidate.name());
        }

        private void hook(Runnable call, String name) {
            if (candidate.remote() == null || context.fileHook() == null) {
                return;
            }
            try {
                call.run();
            } catch (RuntimeException e) {
                throw new FileFailure(FileErrorType.HOOK, name + " failed: " + Exceptions.message(e), null, e);
            }
        }

        private void postSuccessAction() {
            PostSuccessAction action = definition.duplicateProtection().postSuccessAction();
            try {
                switch (action) {
                    case PostSuccessAction.Move(String folder) -> candidate.channel().move(candidate.remote(), folder, clock);
                    case PostSuccessAction.Delete ignored -> candidate.channel().delete(candidate.remote());
                    case PostSuccessAction.None ignored -> {
                    }
                }
            } catch (ChannelException e) {
                Warning warning = new Warning(WarningCode.POST_ACTION_FAILED, e.getMessage(), candidate.name());
                fileWarnings.add(warning);
                warn(warning, null);
            }
        }

        private void rejected(LineRejection rejection) {
            safely(() -> listener.onLineRejected(rejection));
            if (definition.resultReport().mode() != ReportMode.NONE) {
                report(rejection.layout()).add(ReportLine.rejected(rejection.lineNumber(), rejection.rawValues(),
                        rejection.errors()));
            }
        }

        private void applied(String layout, int lineNumber, Map<String, String> rawValues) {
            if (definition.resultReport().mode() == ReportMode.REJECTED_AND_APPLIED) {
                report(layout).add(ReportLine.applied(lineNumber, rawValues));
            }
        }

        private ReportBuffer report(String layout) {
            return reportBuffers.computeIfAbsent(layout, name -> {
                try {
                    return new ReportBuffer(workingDirectory, name);
                } catch (IOException e) {
                    throw new FileFailure(FileErrorType.CHANNEL, "cannot create the report buffer in the working"
                            + " directory: " + e.getMessage(), null, e);
                }
            });
        }

        /** One report per input file and layout with lines (§4.1); a failure is a warning (A9). */
        private void writeReports() {
            ResultReport report = definition.resultReport();
            if (report.mode() == ReportMode.NONE) {
                return;
            }
            for (Layout layout : definition.layouts()) {
                ReportBuffer buffer = reportBuffers.get(layout.name());
                if (buffer == null || buffer.count() == 0) {
                    continue;
                }
                Map<String, String> variables = new LinkedHashMap<>(context.variables());
                variables.put("sourceName", FileNames.baseName(candidate.name()));
                variables.put("layout", layout.name());
                String name = NamePattern.parse(report.namePattern()).render(variables, LocalDateTime.now(clock)) + "."
                        + report.format().extension();
                try (OutputChannel channel = OutputChannels.open(report.channel(), clock, null, secrets)) {
                    Path file = temporary();
                    ReportWriter.write(report.format(), file, buffer.keys(), buffer.lines());
                    Delivered delivered = channel.deliver(file, name, onExistingFile(report.channel()), this::readerWarning);
                    reports.add(new ReportRef(candidate.name(), layout.name(), delivered.fileName(),
                            delivered.destination(), ResultStatus.SUCCESS));
                } catch (ChannelException | IOException | java.io.UncheckedIOException e) {
                    readerWarning(new Warning(WarningCode.REPORT_FAILED, "cannot deliver report " + name + ": "
                            + Exceptions.message(e), candidate.name()));
                    reports.add(new ReportRef(candidate.name(), layout.name(), name, null, ResultStatus.FAILED));
                }
            }
        }

        private void readerWarning(Warning warning) {
            fileWarnings.add(warning);
            warn(warning, null);
        }

        private Path copyToWorkingDirectory(Path source) {
            try {
                Path copy = temporary();
                Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
                return copy;
            } catch (IOException e) {
                throw new FileFailure(FileErrorType.CHANNEL, "cannot copy " + candidate.name()
                        + " to the working directory: " + e.getMessage(), null, e);
            }
        }

        private Path spool(InputStream stream) {
            try (stream) {
                Path copy = temporary();
                Files.copy(stream, copy, StandardCopyOption.REPLACE_EXISTING);
                return copy;
            } catch (IOException e) {
                throw new FileFailure(FileErrorType.CHANNEL, "cannot read the provided input " + candidate.name() + ": "
                        + e.getMessage(), null, e);
            }
        }

        private Path temporary() throws IOException {
            Path path = Files.createTempFile(workingDirectory, "tie-", "-" + candidate.name());
            temporaries.add(path);
            return path;
        }

        private void cleanUp() {
            for (Path temporary : temporaries) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    warn(new Warning(WarningCode.TEMP_CLEANUP_FAILED, "cannot delete working copy " + temporary + ": "
                            + e.getMessage(), candidate.name()), null);
                }
            }
            for (ReportBuffer buffer : reportBuffers.values()) {
                try {
                    buffer.close();
                } catch (IOException e) {
                    warn(new Warning(WarningCode.TEMP_CLEANUP_FAILED, "cannot delete a report buffer: " + e.getMessage(),
                            candidate.name()), null);
                }
            }
            if (candidate.provided() != null) {
                closeQuietly(candidate.provided().stream());
            }
        }

        private FileResult result(FileStatus status, FileError error) {
            RecordDelivery counters = delivery;
            return new FileResult(candidate.name(), candidate.layout().name(), status,
                    counters == null ? 0 : counters.linesRead(), counters == null ? 0 : counters.applied(),
                    counters == null ? 0 : counters.rejected(), counters == null ? 0 : counters.deliveredUpToLine(),
                    error, fileWarnings, candidate.timestamp());
        }
    }

    // ---------------------------------------------------------------- helpers

    private void advanceWatermark(Candidate candidate, Map<String, LocalDateTime> watermarks) {
        if (definition.duplicateProtection().watermark() && candidate.remote() != null && candidate.timestamp() != null) {
            watermarks.merge(candidate.layout().name(), candidate.timestamp(),
                    (current, next) -> next.isAfter(current) ? next : current);
        }
    }

    private static OnExistingFile onExistingFile(ChannelDefinition channel) {
        return switch (channel) {
            case LocalDirectoryChannel local -> local.onExistingFile();
            case SftpChannel sftp -> sftp.onExistingFile();
            case ProvidedStreamChannel ignored -> OnExistingFile.OVERWRITE;
        };
    }

    private FileResult notProcessed(Candidate candidate) {
        return new FileResult(candidate.name(), candidate.layout().name(), FileStatus.NOT_PROCESSED, 0, 0, 0, 0, null,
                List.of(), candidate.timestamp());
    }

    private boolean isCancelled() {
        return context.cancellation() != null && context.cancellation().isCancellationRequested();
    }

    /** Records a run warning and tells the listener; {@code file} collects it for one file when not null. */
    private void warn(Warning warning, List<Warning> file) {
        warnings.add(warning);
        if (file != null) {
            file.add(warning);
        }
        try {
            listener.onWarning(warning);
        } catch (RuntimeException e) {
            warnings.add(new Warning(WarningCode.LISTENER_FAILED, "onWarning failed: " + Exceptions.message(e),
                    warning.fileName()));
        }
    }

    private void safely(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            warn(new Warning(WarningCode.LISTENER_FAILED, "listener failed: " + Exceptions.message(e), null), null);
        }
    }

    private void closeProvidedStreams() {
        if (context != null) {
            context.providedInputs().forEach(input -> closeQuietly(input.stream()));
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** A file to process: from an input channel, or provided by the caller. */
    private record Candidate(String name, Layout layout, LocalDateTime timestamp, RemoteFile remote,
                             InputChannel channel, ChannelDefinition channelDefinition, ProvidedInput provided) {

        static Candidate ofChannel(RemoteFile file, Layout layout, LocalDateTime timestamp, InputChannel channel,
                                   ChannelDefinition definition) {
            return new Candidate(file.name(), layout, timestamp, file, channel, definition, null);
        }

        static Candidate ofProvided(ProvidedInput input, Layout layout) {
            return new Candidate(input.fileName(), layout, null, null, null, null, input);
        }
    }
}
