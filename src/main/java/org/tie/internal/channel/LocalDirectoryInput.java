package org.tie.internal.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.tie.RemoteFile;
import org.tie.config.LocalDirectoryChannel;

/** Regular files directly inside a local directory; the file itself is the local copy. */
public final class LocalDirectoryInput implements InputChannel {

    private final Path directory;

    public LocalDirectoryInput(LocalDirectoryChannel channel) {
        this.directory = channel.directory();
    }

    @Override
    public String description() {
        return "local directory " + directory;
    }

    @Override
    public List<RemoteFile> list() throws ChannelException {
        if (!Files.isDirectory(directory)) {
            throw new ChannelException("directory " + directory + " does not exist", null);
        }
        List<RemoteFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.sorted(Comparator.comparing(Path::toString)).toList()) {
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
                if (attributes.isRegularFile()) {
                    files.add(new RemoteFile(entry.getFileName().toString(), attributes.size(),
                            attributes.lastModifiedTime().toInstant()));
                }
            }
        } catch (IOException e) {
            throw new ChannelException("cannot list " + directory + ": " + e.getMessage(), e);
        }
        return files;
    }

    @Override
    public LocalCopy fetch(RemoteFile file, Path workingDirectory) throws ChannelException {
        Path path = directory.resolve(file.name());
        if (!Files.isRegularFile(path)) {
            throw new ChannelException("file " + file.name() + " no longer exists in " + directory, null);
        }
        return new LocalCopy(path, false);
    }

    @Override
    public void move(RemoteFile file, String folder, Clock clock) throws ChannelException {
        Path target = folder.startsWith("/") ? Path.of(folder) : directory.resolve(folder);
        if (!Files.isDirectory(target)) {
            throw new ChannelException("folder " + target + " does not exist", null);
        }
        Path destination = target.resolve(file.name());
        if (Files.exists(destination)) {
            destination = target.resolve(FileNames.withSuffix(file.name(), clock.millis()));
        }
        try {
            Files.move(directory.resolve(file.name()), destination);
        } catch (IOException e) {
            throw new ChannelException("cannot move " + file.name() + " to " + target + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(RemoteFile file) throws ChannelException {
        try {
            Files.delete(directory.resolve(file.name()));
        } catch (IOException e) {
            throw new ChannelException("cannot delete " + file.name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // nothing to release
    }
}
