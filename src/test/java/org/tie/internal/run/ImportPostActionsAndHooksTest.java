package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.FileErrorType;
import org.tie.FileResult;
import org.tie.FileStatus;
import org.tie.ImportResult;
import org.tie.ReceivedFile;
import org.tie.ResultStatus;
import org.tie.WarningCode;
import org.tie.config.DeliveryStrategy;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.ResultReport;
import org.tie.config.ReportMode;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.FileFormat;
import org.tie.config.PostSuccessAction;
import org.tie.config.ProvidedStreamChannel;
import org.tie.spi.FileHook;
import org.tie.spi.Outcome;
import org.tie.spi.RunListener;

class ImportPostActionsAndHooksTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 26, 12, 0);

    @TempDir
    Path root;

    private ImportHarness h;

    @BeforeEach
    void setUp() {
        h = new ImportHarness(root);
    }

    private static DuplicateProtection with(PostSuccessAction action) {
        return new DuplicateProtection(true, action);
    }

    private Path done() throws IOException {
        return Files.createDirectories(h.in.resolve("done"));
    }

    @Nested
    class PostActions {

        @Test
        @DisplayName("AC-74 three files, the 2nd fails: 1st moved, 2nd failed, 3rd not processed; mark of the 1st")
        void failureStopsTheRun() throws IOException {
            Path done = done();
            h.writeLines("alta_20261026120000.csv", 1);
            h.write("alta_20261027120000.csv", "value\n\"unclosed\n");
            h.writeLines("alta_20261028120000.csv", 1);

            ImportResult result = h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done"))));

            assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
            assertThat(result.files()).extracting(FileResult::status)
                    .containsExactly(FileStatus.SUCCESS, FileStatus.FAILED, FileStatus.NOT_PROCESSED);
            assertThat(h.filesIn(done)).containsExactly("alta_20261026120000.csv");
            assertThat(h.filesIn(h.in)).containsExactly("alta_20261027120000.csv", "alta_20261028120000.csv");
            assertThat(result.watermarks()).containsExactly(java.util.Map.entry("alta", T1));
        }

        @Test
        @DisplayName("AC-83 MOVE leaves the processed file in the folder")
        void move() throws IOException {
            Path done = done();
            h.writeLines("alta_20261026120000.csv", 1);

            h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done/"))));

            assertThat(h.filesIn(done)).containsExactly("alta_20261026120000.csv");
            assertThat(h.filesIn(h.in)).isEmpty();
        }

        @Test
        @DisplayName("AC-84 a name already in the folder gets a .<epochMillis> suffix from the clock")
        void moveCollision() throws IOException {
            Path done = done();
            Files.writeString(done.resolve("alta_20261026120000.csv"), "previous");
            h.writeLines("alta_20261026120000.csv", 1);

            h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done"))));

            assertThat(h.filesIn(done)).containsExactly("alta_20261026120000." + ImportHarness.NOW.toEpochMilli() + ".csv",
                    "alta_20261026120000.csv");
            assertThat(Files.readString(done.resolve("alta_20261026120000.csv"))).isEqualTo("previous");
        }

        @Test
        @DisplayName("AC-85 DELETE never deletes a failed file")
        void deleteOnlyOnSuccess() {
            h.write("alta_20261026120000.csv", "value\n\"unclosed\n");

            h.run(h.altas().duplicateProtection(with(PostSuccessAction.DELETE)));

            assertThat(h.filesIn(h.in)).containsExactly("alta_20261026120000.csv");
        }

        @Test
        @DisplayName("AC-86 AC-194 a MOVE that fails is a warning; the file counts as applied and the run goes on")
        void moveFailure() {
            h.writeLines("alta_20261026120000.csv", 1);
            h.writeLines("alta_20261027120000.csv", 1);

            ImportResult result = h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done"))));

            assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.files()).allSatisfy(file -> {
                assertThat(file.status()).isEqualTo(FileStatus.SUCCESS);
                assertThat(file.warnings()).singleElement()
                        .satisfies(w -> assertThat(w.code()).isEqualTo(WarningCode.POST_ACTION_FAILED));
            });
            assertThat(result.watermarks()).containsEntry("alta", T1.plusDays(1));
            assertThat(h.in.resolve("done")).doesNotExist();
        }

        @Test
        @DisplayName("AC-193 MOVE is relative to the channel directory unless it is absolute")
        void relativeAndAbsoluteMove() throws IOException {
            Path archive = Files.createDirectories(root.resolve("archive"));
            Path done = done();
            h.writeLines("alta_20261026120000.csv", 1);
            h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done"))));
            h.writeLines("alta_20261027120000.csv", 1);

            h.run(h.altas().duplicateProtection(with(PostSuccessAction.move(archive.toString()))));

            assertThat(h.filesIn(done)).containsExactly("alta_20261026120000.csv");
            assertThat(h.filesIn(archive)).containsExactly("alta_20261027120000.csv");
        }

        @Test
        void deleteRemovesTheProcessedFile() {
            h.writeLines("alta_20261026120000.csv", 1);

            h.run(h.altas().duplicateProtection(with(PostSuccessAction.DELETE)));

            assertThat(h.filesIn(h.in)).isEmpty();
        }
    }

    @Nested
    class Hooks {

        @BeforeEach
        void withHook() {
            h.context.fileHook(h.hook);
        }

        @Test
        @DisplayName("AC-112 hook, consumer and listener calls come in order")
        void callOrder() throws IOException {
            Path done = done();
            h.writeLines("alta_20261026120000.csv", 3);
            h.context.listener(new RunListener() {
                @Override
                public void onFileFinished(FileResult result) {
                    h.events.add("finished, moved=" + Files.exists(done.resolve(result.name())));
                }
            });

            h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done"))));

            assertThat(h.events).containsExactly("received alta_20261026120000.csv", "consumer alta:1", "consumer alta:2",
                    "consumer alta:3", "completed alta_20261026120000.csv", "finished, moved=true");
        }

        @Test
        @DisplayName("AC-113 the local copy given to onFileReceived is identical to the file")
        void localCopy() throws IOException {
            Path original = h.writeLines("alta_20261026120000.csv", 3);
            Path kept = root.resolve("kept.csv");
            h.context.fileHook(new FileHook() {
                @Override
                public void onFileReceived(ReceivedFile file) {
                    try {
                        Files.copy(file.localCopy(), kept);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            });

            h.run(h.altas());

            assertThat(Files.readAllBytes(kept)).isEqualTo(Files.readAllBytes(original));
        }

        @Test
        @DisplayName("AC-114 onFileReceived that throws fails the file before any record")
        void receivedThrows() throws IOException {
            Path done = done();
            h.writeLines("alta_20261026120000.csv", 3);
            h.writeLines("alta_20261027120000.csv", 3);
            h.context.fileHook(new FileHook() {
                @Override
                public void onFileReceived(ReceivedFile file) {
                    throw new IllegalStateException("no space");
                }
            });

            ImportResult result = h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done"))));

            assertThat(h.records).isEmpty();
            assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.FAILED, FileStatus.NOT_PROCESSED);
            assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.HOOK);
            assertThat(result.files().getFirst().error().message()).isEqualTo("onFileReceived failed: no space");
            assertThat(h.filesIn(done)).isEmpty();
            assertThat(result.watermarks()).isEmpty();
        }

        @Test
        @DisplayName("AC-115 onFileCompleted that throws: records stay delivered, file failed, not moved, no mark")
        void completedThrows() throws IOException {
            Path done = done();
            h.writeLines("alta_20261026120000.csv", 3);
            h.context.fileHook(new FileHook() {
                @Override
                public void onFileCompleted(FileResult partial) {
                    throw new IllegalStateException("cannot save");
                }
            });

            ImportResult result = h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done"))));

            FileResult file = result.files().getFirst();
            assertThat(h.records).hasSize(3);
            assertThat(file.status()).isEqualTo(FileStatus.FAILED);
            assertThat(file.deliveredUpToLine()).isEqualTo(3);
            assertThat(file.error().type()).isEqualTo(FileErrorType.HOOK);
            assertThat(h.filesIn(done)).isEmpty();
            assertThat(result.watermarks()).isEmpty();
        }

        @Test
        @DisplayName("AC-116 onFileCompleted and onFileFinished get the name's timestamp")
        void timestamps() {
            h.writeLines("alta_20261026120000.csv", 1);

            h.run(h.altas());

            assertThat(h.completed).singleElement().satisfies(partial -> {
                assertThat(partial.timestamp()).isEqualTo(T1);
                assertThat(partial.status()).isEqualTo(FileStatus.SUCCESS);
                assertThat(partial.applied()).isEqualTo(1);
            });
            assertThat(h.finished).singleElement().satisfies(file -> assertThat(file.timestamp()).isEqualTo(T1));
            assertThat(h.received).singleElement().satisfies(file -> {
                assertThat(file.timestamp()).isEqualTo(T1);
                assertThat(file.layout()).isEqualTo("alta");
                assertThat(file.size()).isEqualTo(9);
            });
        }

        @Test
        @DisplayName("AC-117 provided inputs call both hook methods; their timestamp is null")
        void providedInputsCallHooks() {
            provide("p.csv", "value\nv\n");

            ImportResult result = runProvided(h.altas());

            assertThat(h.received).singleElement().satisfies(file -> assertThat(file.timestamp()).isNull());
            assertThat(h.completed).singleElement().satisfies(partial -> assertThat(partial.timestamp()).isNull());
            assertThat(result.files().getFirst().timestamp()).isNull();
        }

        @Test
        @DisplayName("AC-198 provided input: onFileReceived, consumer x3, onFileCompleted, onFileFinished")
        void providedCallOrder() {
            provide("p.csv", "value\nv1\nv2\nv3\n");

            ImportResult result = runProvided(h.altas());

            assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(h.events).containsExactly("started p.csv", "received p.csv", "consumer alta:1", "consumer alta:2",
                    "consumer alta:3", "completed p.csv", "finished p.csv SUCCESS");
            assertThat(h.received).singleElement().satisfies(file -> {
                assertThat(file.name()).isEqualTo("p.csv");
                assertThat(file.layout()).isEqualTo("alta");
                assertThat(file.timestamp()).isNull();
                assertThat(file.size()).isEqualTo(15);
            });
        }

        @Test
        @DisplayName("AC-199 the local copy of a provided input is identical to the stream")
        void providedLocalCopy() {
            byte[] content = "value\nñandú\nv2\n".getBytes(StandardCharsets.UTF_8);
            h.context.providedInput("alta", "p.csv", new ByteArrayInputStream(content));
            List<byte[]> copies = new ArrayList<>();
            h.context.fileHook(new FileHook() {
                @Override
                public void onFileReceived(ReceivedFile file) {
                    copies.add(read(file.localCopy()));
                }
            });

            runProvided(h.altas());

            assertThat(copies).singleElement().isEqualTo(content);
        }

        @Test
        @DisplayName("AC-200 onFileReceived that throws with a provided input: nothing delivered, run stopped")
        void providedReceivedThrows() {
            provide("p1.csv", "value\nv1\n");
            provide("p2.csv", "value\nv1\n");
            h.context.fileHook(new FileHook() {
                @Override
                public void onFileReceived(ReceivedFile file) {
                    throw new IllegalStateException("no space");
                }
            });

            ImportResult result = runProvided(h.altas());

            assertThat(h.records).isEmpty();
            assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
            assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.FAILED, FileStatus.NOT_PROCESSED);
            assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.HOOK);
            assertThat(result.files().getFirst().error().message()).isEqualTo("onFileReceived failed: no space");
        }

        @Test
        @DisplayName("AC-201 onFileCompleted that throws with a provided input: records delivered, failed, no report")
        void providedCompletedThrows() throws IOException {
            Path reports = Files.createDirectories(root.resolve("reports"));
            provide("p.csv", "value\nv1\nv2\nv3\n");
            h.context.fileHook(new FileHook() {
                @Override
                public void onFileCompleted(FileResult partial) {
                    throw new IllegalStateException("cannot save");
                }
            });

            ImportResult result = runProvided(h.altas().resultReport(new ResultReport(ReportMode.REJECTED_AND_APPLIED,
                    FileFormat.CSV, LocalDirectoryChannel.of(reports), "{sourceName}_result")));

            FileResult file = result.files().getFirst();
            assertThat(h.records).hasSize(3);
            assertThat(file.deliveredUpToLine()).isEqualTo(3);
            assertThat(file.status()).isEqualTo(FileStatus.FAILED);
            assertThat(file.error().type()).isEqualTo(FileErrorType.HOOK);
            assertThat(h.filesIn(reports)).isEmpty();
            assertThat(result.reports()).isEmpty();
        }

        @Test
        @DisplayName("AC-202 a provided CSV without a file hook is read from the stream, with no working copy")
        void providedWithoutHookIsNotSpooled() {
            h.context.fileHook(null);
            provide("p.csv", "value\nv1\nv2\n");
            List<List<String>> seen = new ArrayList<>();
            h.behaviour = record -> {
                seen.add(tieFiles());
                return Outcome.APPLIED;
            };

            runProvided(h.altas());

            assertThat(seen).hasSize(2).allSatisfy(files -> assertThat(files).isEmpty());
        }

        @Test
        @DisplayName("AC-203 the spooled copy of a provided input is gone when the file ends, with or without success")
        void spooledCopyIsRemoved() {
            List<List<String>> duringHook = new ArrayList<>();
            FileHook hook = new FileHook() {
                @Override
                public void onFileReceived(ReceivedFile file) {
                    duringHook.add(tieFiles());
                }

                @Override
                public void onFileCompleted(FileResult partial) {
                    if (partial.name().equals("bad.csv")) {
                        throw new IllegalStateException("cannot save");
                    }
                }
            };
            h.context.fileHook(hook);
            provide("ok.csv", "value\nv1\n");
            ImportResult ok = runProvided(h.altas());
            List<String> afterOk = tieFiles();

            h.context = org.tie.ImportContext.builder(h.consumer).listener(h.listener).workingDirectory(h.work)
                    .fileHook(hook);
            provide("bad.csv", "value\nv1\n");
            ImportResult bad = runProvided(h.altas());

            assertThat(ok.files().getFirst().status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(bad.files().getFirst().status()).isEqualTo(FileStatus.FAILED);
            assertThat(duringHook).hasSize(2).allSatisfy(files -> assertThat(files).hasSize(1));
            assertThat(afterOk).isEmpty();
            assertThat(tieFiles()).isEmpty();
        }

        @Test
        @DisplayName("AC-204 provided XLSX with VALIDATE_THEN_DELIVER and a hook: one spool, onFileReceived once, first")
        void providedXlsxSpooledOnce() throws IOException {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet();
                sheet.createRow(0).createCell(0).setCellValue("value");
                sheet.createRow(1).createCell(0).setCellValue("x");
                workbook.write(bytes);
            }
            h.context.providedInput("alta", "p.xlsx", new ByteArrayInputStream(bytes.toByteArray()));
            List<List<String>> duringHook = new ArrayList<>();
            List<java.nio.file.Path> copies = new ArrayList<>();
            h.context.fileHook(new FileHook() {
                @Override
                public void onFileReceived(ReceivedFile file) {
                    h.events.add("received " + file.name());
                    duringHook.add(tieFiles());
                    copies.add(file.localCopy());
                }
            });
            h.tie.transformer("trace", (value, record, direction) -> {
                h.events.add("transform");
                return value;
            });

            ImportResult result = runProvided(h.importOf(ImportHarness.csv("alta", "alta").allowedFormats(FileFormat.XLSX)
                            .fields(List.of(FieldDefinition.builder("value").transformer("trace").build())))
                    .deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER));

            assertThat(result.files().getFirst().applied()).isEqualTo(1);
            assertThat(h.events).filteredOn(event -> event.startsWith("received")).hasSize(1);
            assertThat(h.events).containsSubsequence("received p.xlsx", "transform", "transform", "consumer alta:1");
            assertThat(duringHook).singleElement().satisfies(files -> assertThat(files)
                    .containsExactly(copies.getFirst().getFileName().toString()));
        }

        @Test
        @DisplayName("AC-205 a provided stream that fails while spooling: CHANNEL, no hook, no records")
        void providedStreamFails() {
            h.context.providedInput("alta", "p.csv", new java.io.InputStream() {
                private int left = 8;

                @Override
                public int read() throws IOException {
                    if (left-- > 0) {
                        return 'v';
                    }
                    throw new IOException("connection reset");
                }
            });

            ImportResult result = runProvided(h.altas());

            assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.FAILED);
            assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.CHANNEL);
            assertThat(h.received).isEmpty();
            assertThat(h.records).isEmpty();
        }

        private void provide(String name, String content) {
            h.context.providedInput("alta", name, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        }

        private ImportResult runProvided(org.tie.config.IntegrationDefinition.Builder definition) {
            return h.run(definition.inputChannels(List.of(ProvidedStreamChannel.INSTANCE)));
        }

        private List<String> tieFiles() {
            return h.filesIn(h.work).stream().filter(name -> name.startsWith("tie-")).toList();
        }

        private static byte[] read(Path file) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Test
        @DisplayName("AC-118 with VALIDATE_THEN_DELIVER onFileReceived runs once, before the validation pass")
        void receivedBeforeValidation() {
            h.tie.transformer("trace", (value, record, direction) -> {
                h.events.add("transform");
                return value;
            });
            h.writeLines("alta_20261026120000.csv", 1);

            h.run(h.importOf(ImportHarness.csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                            .fields(List.of(FieldDefinition.builder("value").transformer("trace").build())))
                    .deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER));

            assertThat(h.events).containsSubsequence("received alta_20261026120000.csv", "transform", "transform",
                    "consumer alta:1");
            assertThat(h.events).filteredOn(event -> event.startsWith("received")).hasSize(1);
        }

        @Test
        @DisplayName("AC-119 onFileFinished that throws is a warning and changes nothing")
        void finishedThrows() {
            h.writeLines("alta_20261026120000.csv", 1);
            h.context.listener(new RunListener() {
                @Override
                public void onFileFinished(FileResult result) {
                    throw new IllegalStateException("listener bug");
                }
            });

            ImportResult result = h.run(h.altas().duplicateProtection(with(PostSuccessAction.NONE)));

            assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(result.watermarks()).containsEntry("alta", T1);
            assertThat(result.warnings()).singleElement().satisfies(w -> {
                assertThat(w.code()).isEqualTo(WarningCode.LISTENER_FAILED);
                assertThat(w.message()).contains("listener bug");
            });
        }
    }

    @Nested
    class Invariants {

        /** INV-8 and INV-9 with a consumer that fails at a random place; the seed is the repetition. */
        @RepeatedTest(20)
        void watermarksAndMovesNeverGoPastAFailedFile(RepetitionInfo repetition) throws IOException {
            Random random = new Random(repetition.getCurrentRepetition());
            Path done = done();
            int files = 2 + random.nextInt(4);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < files; i++) {
                String name = "alta_202610" + (10 + i) + "120000.csv";
                h.writeLines(name, 1 + random.nextInt(5));
                names.add(name);
            }
            int failingFile = random.nextInt(files);
            int failingLine = 1 + random.nextInt(2);
            h.behaviour = record -> {
                if (record.sourceFileName().equals(names.get(failingFile)) && record.lineNumber() == failingLine) {
                    throw new IllegalStateException("random failure");
                }
                return Outcome.APPLIED;
            };

            ImportResult result = h.run(h.altas().duplicateProtection(with(PostSuccessAction.move("done")))
                    .consumerErrorPolicy(org.tie.config.ConsumerErrorPolicy.ABORT_FILE));

            List<String> succeeded = result.files().stream().filter(f -> f.status() == FileStatus.SUCCESS)
                    .map(FileResult::name).toList();
            assertThat(h.filesIn(done)).containsExactlyElementsOf(succeeded);
            assertThat(result.files()).extracting(FileResult::status).doesNotContainSequence(FileStatus.FAILED,
                    FileStatus.SUCCESS);
            if (succeeded.isEmpty()) {
                assertThat(result.watermarks()).isEmpty();
            } else {
                assertThat(result.watermarks().get("alta"))
                        .isEqualTo(result.files().stream().filter(f -> f.name().equals(succeeded.getLast()))
                                .findFirst().orElseThrow().timestamp());
            }
            assertThat(h.filesIn(h.work)).isEmpty();
        }
    }
}
