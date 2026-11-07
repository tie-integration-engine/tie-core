package org.tie.internal.format;

import org.tie.FileError;
import org.tie.FileErrorType;

/** Unchecked failure of a whole file while reading or writing it. */
public final class FileFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FileErrorType type;
    private final Integer rowNumber;

    public FileFailure(FileErrorType type, String message, Integer rowNumber, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.rowNumber = rowNumber;
    }

    public FileFailure(FileErrorType type, String message) {
        this(type, message, null, null);
    }

    public static FileFailure structural(String message, Integer rowNumber) {
        return new FileFailure(FileErrorType.STRUCTURAL, message, rowNumber, null);
    }

    public FileErrorType type() {
        return type;
    }

    public Integer rowNumber() {
        return rowNumber;
    }

    public FileError toFileError(Integer lineNumber) {
        return new FileError(type, getMessage(), rowNumber, lineNumber);
    }
}
