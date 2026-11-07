package org.tie.config;

import java.nio.file.Path;

/** A local directory, for input or output. {@code onExistingFile} only applies to output. */
public record LocalDirectoryChannel(Path directory, Long maxFileSize, OnExistingFile onExistingFile)
        implements ChannelDefinition {

    public LocalDirectoryChannel {
        onExistingFile = onExistingFile == null ? OnExistingFile.SUFFIX : onExistingFile;
    }

    public static LocalDirectoryChannel of(Path directory) {
        return new LocalDirectoryChannel(directory, null, null);
    }
}
