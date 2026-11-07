package org.tie.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.FileErrorType;
import org.tie.Warning;
import org.tie.config.OnExistingFile;
import org.tie.internal.channel.ChannelException;

class LocalDirectoryOutputTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

    @TempDir
    Path root;

    private Path destination;
    private Path file;
    private final List<Warning> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        destination = Files.createDirectories(root.resolve("out"));
        file = Files.writeString(root.resolve("local.csv"), "a\n1\n2\n3\n");
    }

    private List<String> destinationFiles() throws IOException {
        try (Stream<Path> files = Files.list(destination)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void inv19AFailedCopyNeverLeavesTheFinalName() throws IOException {
        LocalDirectoryOutput output = new LocalDirectoryOutput(destination, CLOCK, (source, target) -> {
            try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target)) {
                out.write(in.readNBytes(3));
                throw new IOException("connection reset");
            }
        });

        assertThatThrownBy(() -> output.deliver(file, "out.csv", OnExistingFile.SUFFIX, warnings::add))
                .isInstanceOfSatisfying(ChannelException.class, e -> {
                    assertThat(e.type()).isEqualTo(FileErrorType.CHANNEL);
                    assertThat(e).hasMessageContaining("connection reset");
                });
        assertThat(destinationFiles()).isEmpty();
        assertThat(warnings).isEmpty();
        assertThat(Files.readString(file)).isEqualTo("a\n1\n2\n3\n");
    }

    @Test
    void theFileIsWrittenUnderATemporaryNameFirst() throws Exception {
        List<String> seenDuringCopy = new ArrayList<>();
        LocalDirectoryOutput output = new LocalDirectoryOutput(destination, CLOCK, (source, target) -> {
            Files.copy(source, target);
            seenDuringCopy.addAll(destinationFiles());
        });

        Delivered delivered = output.deliver(file, "out.csv", OnExistingFile.SUFFIX, warnings::add);

        assertThat(seenDuringCopy).containsExactly(".out.csv.part");
        assertThat(delivered.fileName()).isEqualTo("out.csv");
        assertThat(destinationFiles()).containsExactly("out.csv");
    }

    @Test
    void aNameCreatedDuringTheCopyIsAlsoACollision() throws Exception {
        LocalDirectoryOutput racing = new LocalDirectoryOutput(destination, CLOCK, (source, target) -> {
            Files.copy(source, target);
            if (!Files.exists(destination.resolve("out.csv"))) {
                Files.writeString(destination.resolve("out.csv"), "someone else");
            }
        });

        Delivered suffixed = racing.deliver(file, "out.csv", OnExistingFile.SUFFIX, warnings::add);
        Files.delete(destination.resolve(suffixed.fileName()));
        Files.delete(destination.resolve("out.csv"));

        assertThat(suffixed.fileName()).isEqualTo("out.1000.csv");
        assertThatThrownBy(() -> racing.deliver(file, "out.csv", OnExistingFile.FAIL, warnings::add))
                .isInstanceOfSatisfying(ChannelException.class,
                        e -> assertThat(e.type()).isEqualTo(FileErrorType.NAME_COLLISION));
        assertThat(destinationFiles()).containsExactly("out.csv");
        assertThat(Files.readString(destination.resolve("out.csv"))).isEqualTo("someone else");
    }

    @Test
    void missingDirectory() {
        LocalDirectoryOutput output = new LocalDirectoryOutput(root.resolve("missing"), CLOCK, Files::copy);

        assertThatThrownBy(() -> output.deliver(file, "out.csv", OnExistingFile.SUFFIX, warnings::add))
                .isInstanceOf(ChannelException.class).hasMessageContaining("does not exist");
    }
}
