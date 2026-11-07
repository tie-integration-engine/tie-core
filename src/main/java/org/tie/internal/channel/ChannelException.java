package org.tie.internal.channel;

import org.tie.FileError;
import org.tie.FileErrorType;

/** A channel operation failed; the message never contains credentials (INV-10). */
public final class ChannelException extends Exception {

    private static final long serialVersionUID = 1L;

    private final FileErrorType type;

    public ChannelException(FileErrorType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public ChannelException(String message, Throwable cause) {
        this(FileErrorType.CHANNEL, message, cause);
    }

    public FileErrorType type() {
        return type;
    }

    public FileError toFileError() {
        return FileError.of(type, getMessage());
    }
}
