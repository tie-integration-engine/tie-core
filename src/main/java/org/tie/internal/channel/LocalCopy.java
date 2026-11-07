package org.tie.internal.channel;

import java.nio.file.Path;

/**
 * A file fully available on the local disk.
 *
 * @param temporary whether TIE created it in the working directory and must delete it
 */
public record LocalCopy(Path path, boolean temporary) {
}
