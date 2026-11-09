package org.tie;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * A file fully available locally (§5.3 {@code FileHook}): the working copy (SFTP), the file itself
 * (local directory) or the spooled copy of a provided input, whose {@code timestamp} is null and whose
 * {@code size} is the number of bytes read from the stream. {@code localCopy} is read-only and only
 * valid during the hook call; copy it to keep it.
 */
public record ReceivedFile(String name, String layout, LocalDateTime timestamp, long size, Path localCopy) {
}
