package org.tie.internal.channel;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.tie.RemoteFile;

/** An input channel of an import: list, fetch, and the post-success actions (§4.1). */
public interface InputChannel extends AutoCloseable {

    /** Human-readable location for messages, without credentials. */
    String description();

    List<RemoteFile> list() throws ChannelException;

    /** The file fully available locally; a download goes into {@code workingDirectory}. */
    LocalCopy fetch(RemoteFile file, Path workingDirectory) throws ChannelException;

    /**
     * Moves the file to {@code folder} (relative to the channel directory unless it starts with
     * {@code /}); the folder must exist. An existing name gets a {@code .<epochMillis>} suffix (AC-84).
     */
    void move(RemoteFile file, String folder, Clock clock) throws ChannelException;

    void delete(RemoteFile file) throws ChannelException;

    @Override
    void close();
}
