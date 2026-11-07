package org.tie.internal.channel.sftp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.DeliveryContext;
import org.tie.DeliveryResult;
import org.tie.ExportContext;
import org.tie.ExportResult;
import org.tie.FileErrorType;
import org.tie.FileResult;
import org.tie.FileStatus;
import org.tie.ImportContext;
import org.tie.ImportResult;
import org.tie.ReceivedFile;
import org.tie.ResultStatus;
import org.tie.Tie;
import org.tie.TypedRecord;
import org.tie.WarningCode;
import org.tie.config.CsvOptions;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.OnExistingFile;
import org.tie.config.PostSuccessAction;
import org.tie.config.SftpChannel;
import org.tie.fixtures.LogCapture;
import org.tie.fixtures.SftpServer;
import org.tie.spi.FileHook;
import org.tie.spi.Outcome;

/** SFTP channels against an embedded MINA SSHD server (failsafe, {@code -Xmx256m}). */
class SftpIT {

    @TempDir
    Path dir;

    private SftpServer server;
    private Path work;
    private final List<TypedRecord> records = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new SftpServer(dir);
        work = Files.createDirectories(dir.resolve("work"));
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    private static Layout.Builder altas() {
        return Layout.builder("alta").fileNamePattern("alta_{timestamp:yyyyMMddHHmmss}").allowedFormats(FileFormat.CSV)
                .csv(CsvOptions.builder().separator(';').build()).field(FieldDefinition.builder("value"));
    }

    private IntegrationDefinition.Builder importFrom(SftpChannel channel) {
        return IntegrationDefinition.importing("sftp").inputChannel(channel).layout(altas());
    }

    private ImportContext.Builder context() {
        return ImportContext.builder(record -> {
            records.add(record);
            return Outcome.APPLIED;
        }).workingDirectory(work);
    }

    private ImportResult importFiles(Tie tie, IntegrationDefinition.Builder definition, ImportContext.Builder context) {
        return tie.importFiles(definition.build(), context.build());
    }

    private ImportResult importFiles(IntegrationDefinition.Builder definition) {
        return importFiles(Tie.builder().build(), definition, context());
    }

    private Path remote(String name, String content) throws IOException {
        return Files.writeString(server.in().resolve(name), content, StandardCharsets.UTF_8);
    }

    private static List<String> files(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    // ---------------------------------------------------------------- import

    @Test
    void importsMovesAndDeletesRemoteFiles() throws IOException {
        Files.createDirectories(server.in().resolve("done"));
        remote("alta_20261026120000.csv", "value\na\nb\n");
        remote("alta_20261027120000.csv", "value\nc\n");

        ImportResult result = importFiles(importFrom(server.channel("/in").build())
                .duplicateProtection(new DuplicateProtection(true, PostSuccessAction.move("done"))));

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(records).extracting(r -> r.get("value")).containsExactly("a", "b", "c");
        assertThat(files(server.in().resolve("done"))).containsExactly("alta_20261026120000.csv", "alta_20261027120000.csv");
        assertThat(result.watermarks()).containsEntry("alta", LocalDateTime.of(2026, 10, 27, 12, 0));
        assertThat(files(work)).isEmpty();
    }

    @Test
    @DisplayName("AC-193 SFTP MOVE: relative to remoteDirectory or absolute")
    void moveRelativeAndAbsolute() throws IOException {
        Files.createDirectories(server.in().resolve("done"));
        Files.createDirectories(server.root().resolve("archive"));
        remote("alta_20261026120000.csv", "value\na\n");
        importFiles(importFrom(server.channel("/in").build())
                .duplicateProtection(new DuplicateProtection(false, PostSuccessAction.move("done/"))));
        remote("alta_20261027120000.csv", "value\nb\n");

        importFiles(importFrom(server.channel("/in").build())
                .duplicateProtection(new DuplicateProtection(false, PostSuccessAction.move("/archive"))));

        assertThat(files(server.in().resolve("done"))).containsExactly("alta_20261026120000.csv");
        assertThat(files(server.root().resolve("archive"))).containsExactly("alta_20261027120000.csv");
    }

    @Test
    @DisplayName("AC-194 SFTP MOVE to a missing folder is a warning and the folder is not created")
    void moveToMissingFolder() throws IOException {
        remote("alta_20261026120000.csv", "value\na\n");

        ImportResult result = importFiles(importFrom(server.channel("/in").build())
                .duplicateProtection(new DuplicateProtection(true, PostSuccessAction.move("done"))));

        assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.SUCCESS);
        assertThat(result.files().getFirst().warnings()).extracting(w -> w.code()).containsExactly(WarningCode.POST_ACTION_FAILED);
        assertThat(result.watermarks()).containsKey("alta");
        assertThat(server.in().resolve("done")).doesNotExist();
    }

    @Test
    @DisplayName("AC-20 an unsafe remote name is ignored with a warning and never downloaded")
    void unsafeRemoteName() throws IOException {
        remote("alta..20261026120000.csv", "value\na\n");

        ImportResult result = importFiles(IntegrationDefinition.importing("sftp").inputChannel(server.channel("/in").build())
                .layout(altas().fileNamePattern("alta..{timestamp:yyyyMMddHHmmss}")));

        assertThat(result.files()).isEmpty();
        assertThat(result.warnings()).extracting(w -> w.code()).containsExactly(WarningCode.UNSAFE_FILE_NAME);
        assertThat(files(work)).isEmpty();
    }

    @Test
    @DisplayName("AC-21 an SFTP file above maxFileSize fails before it is downloaded")
    void maxFileSize() throws IOException {
        remote("alta_20261026120000.csv", "value\n" + "x".repeat(1001 - 6));

        ImportResult result = importFiles(importFrom(server.channel("/in").maxFileSize(1000L).build()));

        assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.FILE_TOO_LARGE);
        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("AC-113 the local copy of an SFTP file is identical to the remote one")
    void localCopyIsIdentical() throws IOException {
        Path original = remote("alta_20261026120000.csv", "value\nñandú\n€\n");
        Path kept = dir.resolve("kept.csv");

        importFiles(Tie.builder().build(), importFrom(server.channel("/in").build()), context().fileHook(new FileHook() {
            @Override
            public void onFileReceived(ReceivedFile file) {
                try {
                    Files.copy(file.localCopy(), kept);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }));

        assertThat(Files.readAllBytes(kept)).isEqualTo(Files.readAllBytes(original));
        assertThat(files(work)).isEmpty();
    }

    @Test
    void aFileThatDisappearsAfterListingFails() throws IOException {
        Path file = remote("alta_20261026120000.csv", "value\na\n");

        ImportResult result = importFiles(Tie.builder().build(), importFrom(server.channel("/in").build()),
                context().alreadyProcessed(remote -> {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    return false;
                }));

        assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.FAILED);
        assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.CHANNEL);
        assertThat(result.files().getFirst().error().message()).contains("no longer exists");
    }

    @Test
    void aTimeoutDuringTheDownloadDeliversNothing() throws IOException {
        remote("alta_20261026120000.csv", "value\na\nb\n");
        server.delayReads(Duration.ofSeconds(3));

        ImportResult result = importFiles(importFrom(server.channel("/in").timeout(Duration.ofSeconds(1)).build()));

        assertThat(result.files().getFirst().status()).isEqualTo(FileStatus.FAILED);
        assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.CHANNEL);
        assertThat(records).isEmpty();
        assertThat(files(work)).isEmpty();
    }

    // ---------------------------------------------------------------- connection and security

    @Test
    @DisplayName("AC-152 a server that cannot be reached fails the run before any file")
    void serverDown() throws IOException {
        remote("alta_20261026120000.csv", "value\na\n");
        SftpChannel channel = server.channel("/in").build();
        server.stop();

        ImportResult result = importFiles(Tie.builder().build(), importFrom(channel),
                context().watermark("alta", LocalDateTime.of(2026, 1, 1, 0, 0)));

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.files()).isEmpty();
        assertThat(result.error().type()).isEqualTo(FileErrorType.CHANNEL);
        assertThat(result.watermarks()).containsExactly(java.util.Map.entry("alta", LocalDateTime.of(2026, 1, 1, 0, 0)));
    }

    @Test
    @DisplayName("AC-100 AC-155 a host key that differs from known_hosts refuses the connection")
    void hostKeyMismatch() throws IOException {
        try (SftpServer other = new SftpServer(Files.createDirectories(dir.resolve("other")))) {
            Files.writeString(server.knownHosts(), Files.readString(other.knownHosts()).replace(
                    "[localhost]:" + other.port(), "[localhost]:" + server.port()));
        }

        ImportResult result = importFiles(importFrom(server.channel("/in").build()));

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.error().type()).isEqualTo(FileErrorType.HOST_KEY);
        assertThat(result.error().message()).startsWith("host key verification failed");
    }

    @Test
    void theRightFingerprintConnects() {
        ImportResult result = importFiles(importFrom(server.channel("/in").knownHostsFile(null)
                .hostKeyFingerprint(server.fingerprint()).build()));

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
    }

    @Test
    @DisplayName("AC-101 insecureAcceptAnyHostKey connects and logs a WARN naming the option on every connection")
    void insecureHostKey() {
        SftpChannel insecure = server.channel("/in").knownHostsFile(null).insecureAcceptAnyHostKey(true).build();

        try (LogCapture logs = new LogCapture(Level.WARN)) {
            assertThat(importFiles(importFrom(insecure)).status()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(importFiles(importFrom(insecure)).status()).isEqualTo(ResultStatus.SUCCESS);

            assertThat(logs.events()).filteredOn(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("insecureAcceptAnyHostKey")).hasSize(2);
        }
    }

    @Test
    @DisplayName("AC-102 a rejected ${secret:…} password never reaches errors or logs")
    void secretNeverLogged() {
        String wrong = "wr0ng-Pa55-value";
        List<char[]> provided = new ArrayList<>();
        Tie tie = Tie.builder().secretProvider(name -> {
            char[] secret = wrong.toCharArray();
            provided.add(secret);
            return secret;
        }).build();

        ImportResult result;
        try (LogCapture logs = new LogCapture(Level.TRACE)) {
            result = importFiles(tie, importFrom(server.channel("/in").password("${secret:sftp}").build()), context());

            assertThat(logs.lines()).isNotEmpty().noneMatch(line -> line.contains(wrong));
        }
        assertThat(result.error().type()).isEqualTo(FileErrorType.AUTHENTICATION);
        assertThat(result.error().message()).doesNotContain(wrong).doesNotContain("${secret");
        assertThat(provided).singleElement().satisfies(secret -> assertThat(new String(secret)).isEqualTo("\0".repeat(wrong.length())));
    }

    @Test
    @DisplayName("AC-154 a rejected password is an AUTHENTICATION error without the password")
    void wrongPassword() {
        ImportResult result = importFiles(importFrom(server.channel("/in").password("not-the-password").build()));

        assertThat(result.error().type()).isEqualTo(FileErrorType.AUTHENTICATION);
        assertThat(result.error().message()).doesNotContain("not-the-password")
                .isEqualTo("authentication failed for sftp://tie@localhost:" + server.port() + "/in");
    }

    @Test
    @DisplayName("AC-103 at INFO no log line contains record values")
    void noRecordValuesInLogs() throws IOException {
        remote("alta_20261026120000.csv", "value\nvery-private-value\n");

        try (LogCapture logs = new LogCapture(Level.INFO)) {
            importFiles(importFrom(server.channel("/in").build()));

            assertThat(logs.lines()).noneMatch(line -> line.contains("very-private-value"));
            assertThat(logs.lines()).anyMatch(line -> line.contains("alta_20261026120000.csv"));
        }
        assertThat(records).extracting(r -> r.get("value")).containsExactly("very-private-value");
    }

    @Test
    @DisplayName("AC-191 a private key file with passphrase authenticates")
    void privateKeyWithPassphrase() throws Exception {
        Path privateKey = dir.resolve("id_rsa");
        Path publicKey = dir.resolve("id_rsa.pub");
        KeyPair pair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
        pair.writePrivateKey(privateKey.toString(), "k3y-phrase".getBytes(StandardCharsets.UTF_8));
        pair.writePublicKey(publicKey.toString(), "tie-test");
        pair.dispose();
        server.authorize(AuthorizedKeyEntry.readAuthorizedKeys(publicKey).getFirst()
                .resolvePublicKey(null, PublicKeyEntryResolver.FAILING));
        remote("alta_20261026120000.csv", "value\na\n");

        ImportResult result = importFiles(importFrom(server.channel("/in").password(null).privateKeyFile(privateKey)
                .privateKeyPassphrase("k3y-phrase").build()));

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(records).hasSize(1);
        ImportResult wrongPhrase = importFiles(importFrom(server.channel("/in").password(null).privateKeyFile(privateKey)
                .privateKeyPassphrase("nope").build()));
        assertThat(wrongPhrase.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(wrongPhrase.error().message()).doesNotContain("nope");
    }

    // ---------------------------------------------------------------- delivery

    @Test
    @DisplayName("AC-132 deliverFile uploads an identical copy and leaves the local file untouched")
    void deliverFile() throws IOException {
        Path local = Files.writeString(dir.resolve("r.csv"), "a\n1\n2\n");

        DeliveryResult result = Tie.builder().build().deliverFile(server.channel("/out").build(), local, "res_1.csv",
                DeliveryContext.EMPTY);

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.destination()).isEqualTo("sftp://tie@localhost:" + server.port() + "/out");
        assertThat(Files.readString(server.out().resolve("res_1.csv"))).isEqualTo("a\n1\n2\n");
        assertThat(Files.readString(local)).isEqualTo("a\n1\n2\n");
        assertThat(files(server.out())).containsExactly("res_1.csv");
    }

    @Test
    @DisplayName("AC-133 deliverFile to a server that is down returns FAILED and does not throw")
    void deliverToServerDown() throws IOException {
        Path local = Files.writeString(dir.resolve("r.csv"), "a\n");
        SftpChannel channel = server.channel("/out").build();
        server.stop();

        DeliveryResult result = Tie.builder().build().deliverFile(channel, local, "r.csv", DeliveryContext.EMPTY);

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.error().type()).isEqualTo(FileErrorType.CHANNEL);
        assertThat(Files.readString(local)).isEqualTo("a\n");
    }

    @Test
    @DisplayName("AC-135 deliverFile with a different fingerprint fails host key verification")
    void deliverWithWrongFingerprint() throws IOException {
        Path local = Files.writeString(dir.resolve("r.csv"), "a\n");

        DeliveryResult result = Tie.builder().build().deliverFile(server.channel("/out").knownHostsFile(null)
                .hostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").build(), local, "r.csv",
                DeliveryContext.EMPTY);

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.error().type()).isEqualTo(FileErrorType.HOST_KEY);
        assertThat(result.error().message()).startsWith("host key verification failed");
        assertThat(files(server.out())).isEmpty();
    }

    @Test
    @DisplayName("AC-183 a connection cut during the upload leaves no file with the final name")
    void cutDuringUpload() throws IOException {
        byte[] content = new byte[2 * 1024 * 1024];
        Arrays.fill(content, (byte) 'x');
        Path local = Files.write(dir.resolve("big.csv"), content);
        server.cutUploads(true);

        DeliveryResult delivery = Tie.builder().build().deliverFile(server.channel("/out").build(), local, "big.csv",
                DeliveryContext.EMPTY);
        ExportResult export = Tie.builder().build().exportRecords(IntegrationDefinition.exporting("e")
                        .outputChannel(server.channel("/out").build()).outputNamePattern("out")
                        .layout(Layout.builder("out").allowedFormats(FileFormat.CSV).field(FieldDefinition.builder("a")))
                        .build(), List.of(java.util.Map.<String, Object>of("a", "x".repeat(1_000_000))).iterator(),
                ExportContext.builder().workingDirectory(work).build());

        assertThat(delivery.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(delivery.error().type()).isEqualTo(FileErrorType.CHANNEL);
        assertThat(export.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(export.error().type()).isEqualTo(FileErrorType.CHANNEL);
        assertThat(files(server.out())).doesNotContain("big.csv", "out.csv");
        assertThat(files(work)).isEmpty();
    }

    @Test
    void exportCollisionsOverSftp() throws IOException {
        Files.writeString(server.out().resolve("out.csv"), "previous");
        IntegrationDefinition.Builder definition = IntegrationDefinition.exporting("e").outputNamePattern("out")
                .layout(Layout.builder("out").allowedFormats(FileFormat.CSV).field(FieldDefinition.builder("a")));
        Tie tie = Tie.builder().clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(5), java.time.ZoneOffset.UTC))
                .build();
        java.util.function.Function<OnExistingFile, ExportResult> run = policy -> tie.exportRecords(
                definition.outputChannel(server.channel("/out").onExistingFile(policy).build()).build(),
                List.of(java.util.Map.<String, Object>of("a", "new")).iterator(),
                ExportContext.builder().workingDirectory(work).build());

        assertThat(run.apply(OnExistingFile.SUFFIX).fileName()).isEqualTo("out.5.csv");
        assertThat(run.apply(OnExistingFile.FAIL).error().type()).isEqualTo(FileErrorType.NAME_COLLISION);
        assertThat(Files.readString(server.out().resolve("out.csv"))).isEqualTo("previous");
        assertThat(run.apply(OnExistingFile.OVERWRITE).fileName()).isEqualTo("out.csv");
        assertThat(Files.readString(server.out().resolve("out.csv"))).isEqualTo("a\r\nnew\r\n");
        assertThat(files(server.out())).containsExactly("out.5.csv", "out.csv");
    }

    @Test
    void reportsAreDeliveredOverSftp() throws IOException {
        remote("alta_20261026120000.csv", "value\n \nb\n");

        ImportResult result = importFiles(IntegrationDefinition.importing("sftp").inputChannel(server.channel("/in").build())
                .layout(altas().fields(List.of(FieldDefinition.builder("value").onEmpty(org.tie.config.OnEmpty.ERROR).build())))
                .resultReport(new org.tie.config.ResultReport(org.tie.config.ReportMode.REJECTED_AND_APPLIED,
                        FileFormat.CSV, server.channel("/out").build(), "{sourceName}_report")));

        assertThat(result.reports()).singleElement().satisfies(report -> {
            assertThat(report.status()).isEqualTo(ResultStatus.SUCCESS);
            assertThat(report.destination()).isEqualTo("sftp://tie@localhost:" + server.port() + "/out");
        });
        assertThat(Files.readString(server.out().resolve("alta_20261026120000_report.csv"))).isEqualTo("""
                value,LINE_NUMBER,RESULT,REASON,CODES\r
                " ",1,REJECTED,value: required field is empty,REQUIRED_EMPTY\r
                b,2,APPLIED,,\r
                """);
        assertThat(files(server.out())).containsExactly("alta_20261026120000_report.csv");
    }
}
