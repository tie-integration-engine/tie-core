package org.tie.internal.delivery;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.function.Consumer;
import org.tie.FileErrorType;
import org.tie.Warning;
import org.tie.WarningCode;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnExistingFile;
import org.tie.internal.channel.ChannelException;
import org.tie.internal.channel.FileNames;

/** Delivery to a local directory: copy to {@code .<name>.part}, then rename to the final name. */
public final class LocalDirectoryOutput implements OutputChannel {

    /** Copies the local file to the temporary destination; replaceable in tests to simulate failures. */
    @FunctionalInterface
    interface Copier {
        void copy(Path source, Path target) throws IOException;
    }

    private final Path directory;
    private final Clock clock;
    private final Copier copier;

    public LocalDirectoryOutput(LocalDirectoryChannel channel, Clock clock) {
        this(channel.directory(), clock, (source, target) -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING));
    }

    LocalDirectoryOutput(Path directory, Clock clock, Copier copier) {
        this.directory = directory;
        this.clock = clock;
        this.copier = copier;
    }

    @Override
    public Delivered deliver(Path file, String name, OnExistingFile onExistingFile, Consumer<Warning> warnings)
            throws ChannelException {
        if (!Files.isDirectory(directory)) {
            throw new ChannelException("directory " + directory + " does not exist", null);
        }
        Path temporary = directory.resolve("." + name + ".part");
        try {
            copier.copy(file, temporary);
        } catch (IOException e) {
            removeQuietly(temporary, name, warnings);
            throw new ChannelException("cannot write " + name + " to " + directory + ": " + e.getMessage(), e);
        }
        try {
            return new Delivered(rename(temporary, name, onExistingFile).getFileName().toString(), directory.toString());
        } catch (ChannelException e) {
            removeQuietly(temporary, name, warnings);
            throw e;
        }
    }

    private Path rename(Path temporary, String name, OnExistingFile onExistingFile) throws ChannelException {
        Path target = directory.resolve(name);
        try {
            if (onExistingFile == OnExistingFile.OVERWRITE) {
                return move(temporary, target, true);
            }
            if (Files.exists(target)) {
                if (onExistingFile == OnExistingFile.FAIL) {
                    throw collision(name);
                }
                target = directory.resolve(FileNames.withSuffix(name, clock.millis()));
            }
            try {
                return move(temporary, target, false);
            } catch (FileAlreadyExistsException race) {
                if (onExistingFile == OnExistingFile.FAIL) {
                    throw collision(name);
                }
                return move(temporary, directory.resolve(FileNames.withSuffix(name, clock.millis())), false);
            }
        } catch (IOException e) {
            throw new ChannelException("cannot rename " + temporary.getFileName() + " to " + name + ": " + e.getMessage(), e);
        }
    }

    private static Path move(Path source, Path target, boolean replace) throws IOException {
        if (replace) {
            try {
                return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return Files.move(source, target);
    }

    private ChannelException collision(String name) {
        return new ChannelException(FileErrorType.NAME_COLLISION, name + " already exists in " + directory, null);
    }

    private static void removeQuietly(Path temporary, String name, Consumer<Warning> warnings) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException e) {
            warnings.accept(new Warning(WarningCode.TEMP_CLEANUP_FAILED, "cannot delete temporary file " + temporary
                    + ": " + e.getMessage(), name));
        }
    }

    @Override
    public void close() {
        // nothing to release
    }
}
