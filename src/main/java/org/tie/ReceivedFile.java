package org.tie;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * A file of an input channel, fully available locally (§5.3 {@code FileHook}). {@code localCopy} is
 * read-only and only valid during the hook call; copy it to keep it.
 */
public record ReceivedFile(String name, String layout, LocalDateTime timestamp, long size, Path localCopy) {
}
