package org.tie;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tie.config.ColumnLocation;
import org.tie.config.ConsumerErrorPolicy;
import org.tie.config.CsvOptions;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnEmpty;
import org.tie.config.OnExistingFile;
import org.tie.config.PostSuccessAction;
import org.tie.config.RejectionThreshold;
import org.tie.config.ReportMode;
import org.tie.config.ResultReport;
import org.tie.config.XmlOptions;
import org.tie.fixtures.SftpServer;
import org.tie.spi.FileHook;
import org.tie.spi.Outcome;
import org.tie.spi.RunListener;

/**
 * AC-197: every {@link FileErrorType} and every {@link WarningCode} can be produced through the public
 * API. The switches have no default branch, so a new enum value does not compile without its case.
 */
@DisplayName("AC-197 catalogs of file error types and warning codes")
class CatalogIT {

    @TempDir
    static Path root;

    private static SftpServer server;
    private static int counter;

    @BeforeAll
    static void startServer() throws IOException {
        server = new SftpServer(root);
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.close();
    }

    @ParameterizedTest
    @EnumSource(FileErrorType.class)
    void everyFileErrorType(FileErrorType type) throws IOException {
        FileError error = switch (type) {
            case STRUCTURAL -> fileError(csvImport("value\n\"unclosed\n"));
            case ENCODING -> fileError(run(importOf(bytes("alta_1.csv",
                    new byte[]{'v', 'a', 'l', 'u', 'e', '\n', (byte) 0xFF, '\n'})), context()));
            case UNSAFE_CONTENT -> fileError(run(importOf(file("alta_1.xml",
                    "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r><rec>&x;</rec></r>"), FileFormat.XML),
                    context()));
            case EXTENSION_NOT_ALLOWED -> fileError(run(importOf(file("alta_1.txt", "value\nv\n")), context()));
            case FILE_TOO_LARGE -> {
                Path in = file("alta_1.csv", "value\n" + "v\n".repeat(100));
                yield fileError(run(definition(new LocalDirectoryChannel(in, 10L, null), FileFormat.CSV), context()));
            }
            case DUPLICATE_TIMESTAMP -> {
                Path in = file("alta_20261026.csv", "value\nv\n");
                Files.writeString(in.resolve("alta_20261026.xml"), "<r/>");
                yield run(definition(LocalDirectoryChannel.of(in), FileFormat.CSV, FileFormat.XML)
                        .layouts(List.of(layout(FileFormat.CSV, FileFormat.XML)
                                .fileNamePattern("alta_{timestamp:yyyyMMdd}").build())), context()).error();
            }
            case AMBIGUOUS_LAYOUT -> {
                Path in = file("alta_1.csv", "value\nv\n");
                IntegrationDefinition.Builder definition = definition(LocalDirectoryChannel.of(in), FileFormat.CSV)
                        .layout(Layout.builder("other").fileNamePattern("alta_{timestamp:yyyyMMdd}")
                                .allowedFormats(FileFormat.CSV));
                yield run(definition.layouts(List.of(layout(FileFormat.CSV).fileNamePattern("alta_1").build(),
                        Layout.builder("other").fileNamePattern("alta_{n}").allowedFormats(FileFormat.CSV).build())),
                        context().variable("n", "1")).error();
            }
            case CHANNEL -> run(definition(LocalDirectoryChannel.of(root.resolve("missing")), FileFormat.CSV), context()).error();
            case AUTHENTICATION -> run(definition(server.channel("/in").password("wrong").build(), FileFormat.CSV),
                    context()).error();
            case HOST_KEY -> run(definition(server.channel("/in").knownHostsFile(null)
                    .hostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").build(), FileFormat.CSV),
                    context()).error();
            case NAME_COLLISION -> {
                Path out = Files.createDirectories(root.resolve("collision" + (++counter)));
                Files.writeString(out.resolve("x.csv"), "old");
                yield Tie.builder().build().deliverFile(new LocalDirectoryChannel(out, null, OnExistingFile.FAIL),
                        file("local.csv", "x").resolve("local.csv"), "x.csv", DeliveryContext.EMPTY).error();
            }
            case THRESHOLD -> fileError(run(csvDefinition("value\n\n \n", OnEmpty.ERROR)
                    .rejectionThreshold(RejectionThreshold.maxRejected(0)), context()));
            case CONSUMER -> fileError(run(csvDefinition("value\nv\n", OnEmpty.EMPTY)
                    .consumerErrorPolicy(ConsumerErrorPolicy.ABORT_FILE), ImportContext.builder(record -> {
                        throw new IllegalStateException("db");
                    }).workingDirectory(root)));
            case HOOK -> fileError(run(csvDefinition("value\nv\n", OnEmpty.EMPTY), context().fileHook(new FileHook() {
                @Override
                public void onFileReceived(ReceivedFile file) {
                    throw new IllegalStateException("hook");
                }
            })));
        };

        assertThat(error).isNotNull();
        assertThat(error.type()).isEqualTo(type);
        assertThat(error.message()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(WarningCode.class)
    void everyWarningCode(WarningCode code) throws IOException {
        List<Warning> warnings = switch (code) {
            case HEADER_MISMATCH -> run(positions("x;b\n1;2\n", "a", "b"), context()).warnings();
            case EXTRA_COLUMNS -> run(positions("a;b;c\n1;2;3\n", "a", "b"), context()).warnings();
            case MISSING_COLUMN -> run(definition(LocalDirectoryChannel.of(file("alta_1.csv", "a;b\n1;2\n")), FileFormat.CSV)
                    .layouts(List.of(layout(FileFormat.CSV).columnLocation(ColumnLocation.LETTER)
                            .fields(List.of(FieldDefinition.builder("a").column("C").build())).build())), context()).warnings();
            case UNSAFE_FILE_NAME -> run(definition(LocalDirectoryChannel.of(file("alta..1.csv", "value\n")), FileFormat.CSV),
                    context()).warnings();
            case POST_ACTION_FAILED -> run(csvDefinition("value\nv\n", OnEmpty.EMPTY)
                    .duplicateProtection(new DuplicateProtection(false, PostSuccessAction.move("missing"))), context()).warnings();
            case REPORT_FAILED -> {
                Path reports = Files.createDirectories(root.resolve("reports" + (++counter)));
                Files.writeString(reports.resolve("alta_1_r.csv"), "old");
                yield run(csvDefinition("value\n \n", OnEmpty.ERROR).resultReport(new ResultReport(ReportMode.REJECTED,
                        FileFormat.CSV, new LocalDirectoryChannel(reports, null, OnExistingFile.FAIL), "{sourceName}_r")),
                        context()).warnings();
            }
            case LISTENER_FAILED -> run(csvDefinition("value\nv\n", OnEmpty.EMPTY), context().listener(new RunListener() {
                @Override
                public void onFileFinished(FileResult result) {
                    throw new IllegalStateException("listener");
                }
            })).warnings();
            case TEMP_CLEANUP_FAILED -> {
                server.cutUploads(true);
                try {
                    byte[] content = new byte[1024 * 1024];
                    Arrays.fill(content, (byte) 'x');
                    yield Tie.builder().build().deliverFile(server.channel("/out").build(),
                            Files.write(root.resolve("big" + (++counter)), content), "big.csv", DeliveryContext.EMPTY).warnings();
                } finally {
                    server.cutUploads(false);
                }
            }
        };

        assertThat(warnings).extracting(Warning::code).contains(code);
        assertThat(warnings).filteredOn(w -> w.code() == code).allSatisfy(w -> assertThat(w.message()).isNotBlank());
    }

    // ---------------------------------------------------------------- helpers

    private static Path file(String name, String content) throws IOException {
        return bytes(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path bytes(String name, byte[] content) throws IOException {
        Path in = Files.createDirectories(root.resolve("in" + (++counter)));
        Files.write(in.resolve(name), content);
        return in;
    }

    private static Layout.Builder layout(FileFormat... formats) {
        return Layout.builder("alta").fileNamePattern("alta_1").allowedFormats(formats)
                .csv(CsvOptions.builder().separator(';').build()).xml(XmlOptions.of("rec"))
                .field(FieldDefinition.builder("value"));
    }

    private static IntegrationDefinition.Builder definition(org.tie.config.ChannelDefinition channel, FileFormat... formats) {
        return IntegrationDefinition.importing("catalog").inputChannel(channel).layout(layout(formats));
    }

    private static IntegrationDefinition.Builder importOf(Path in, FileFormat... formats) {
        return definition(LocalDirectoryChannel.of(in), formats.length == 0 ? new FileFormat[]{FileFormat.CSV} : formats);
    }

    private static ImportResult csvImport(String content) throws IOException {
        return run(importOf(file("alta_1.csv", content)), context());
    }

    private static IntegrationDefinition.Builder csvDefinition(String content, OnEmpty onEmpty) throws IOException {
        return importOf(file("alta_1.csv", content)).layouts(List.of(layout(FileFormat.CSV)
                .fields(List.of(FieldDefinition.builder("value").onEmpty(onEmpty).build())).build()));
    }

    private static IntegrationDefinition.Builder positions(String content, String... fields) throws IOException {
        return importOf(file("alta_1.csv", content)).layouts(List.of(layout(FileFormat.CSV)
                .columnLocation(ColumnLocation.POSITION)
                .fields(Arrays.stream(fields).map(name -> FieldDefinition.builder(name).build()).toList()).build()));
    }

    private static ImportContext.Builder context() {
        return ImportContext.builder(record -> Outcome.APPLIED).workingDirectory(root);
    }

    private static ImportResult run(IntegrationDefinition.Builder definition, ImportContext.Builder context) {
        return Tie.builder().build().importFiles(definition.build(), context.build());
    }

    private static FileError fileError(ImportResult result) {
        assertThat(result.files()).as("files of %s", result).isNotEmpty();
        return Objects.requireNonNull(result.files().getFirst().error(), "no error in " + result.files().getFirst());
    }
}
