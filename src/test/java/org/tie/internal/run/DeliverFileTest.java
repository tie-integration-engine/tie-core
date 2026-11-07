package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.ConfigurationException;
import org.tie.DeliveryContext;
import org.tie.DeliveryResult;
import org.tie.FileErrorType;
import org.tie.ResultStatus;
import org.tie.Tie;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnExistingFile;
import org.tie.config.ProvidedStreamChannel;

class DeliverFileTest {

    @TempDir
    Path root;

    private Path out;
    private Path file;
    private final Tie tie = Tie.builder().build();

    @BeforeEach
    void setUp() throws IOException {
        out = Files.createDirectories(root.resolve("out"));
        file = Files.writeString(root.resolve("r.csv"), "a\n1\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
    }

    @Test
    void deliversACopyAndLeavesTheLocalFileUntouched() throws IOException {
        DeliveryResult result = tie.deliverFile(LocalDirectoryChannel.of(out), file, "res_1.csv", DeliveryContext.EMPTY);

        assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(result.fileName()).isEqualTo("res_1.csv");
        assertThat(result.destination()).isEqualTo(out.toString());
        assertThat(Files.readString(out.resolve("res_1.csv"))).isEqualTo("a\n1\n");
        assertThat(Files.readString(file)).isEqualTo("a\n1\n");
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(1_000_000);
    }

    @Test
    void deliveryFailuresAreReturned() throws IOException {
        Files.writeString(out.resolve("res_1.csv"), "previous");

        DeliveryResult result = tie.deliverFile(new LocalDirectoryChannel(out, null, OnExistingFile.FAIL), file, "res_1.csv",
                DeliveryContext.EMPTY);

        assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
        assertThat(result.error().type()).isEqualTo(FileErrorType.NAME_COLLISION);
        assertThat(Files.readString(file)).isEqualTo("a\n1\n");
    }

    @Test
    @DisplayName("AC-134 an unsafe remote name is a configuration error")
    void unsafeName() {
        assertThatThrownBy(() -> tie.deliverFile(LocalDirectoryChannel.of(out), file, "../x.csv", DeliveryContext.EMPTY))
                .isInstanceOfSatisfying(ConfigurationException.class,
                        e -> assertThat(e.getProblems()).containsExactly("remoteName '../x.csv' is not a safe file name"));
    }

    @Test
    @DisplayName("AC-136 a ProvidedStreamChannel cannot receive a file")
    void providedStreamChannel() {
        assertThatThrownBy(() -> tie.deliverFile(ProvidedStreamChannel.INSTANCE, file, "x.csv", DeliveryContext.EMPTY))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("AC-137 a missing local file is a configuration error")
    void missingFile() {
        assertThatThrownBy(() -> tie.deliverFile(LocalDirectoryChannel.of(out), root.resolve("nope.csv"), "x.csv",
                DeliveryContext.EMPTY)).isInstanceOf(ConfigurationException.class);
    }
}
