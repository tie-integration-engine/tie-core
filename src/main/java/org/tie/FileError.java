package org.tie;

import java.util.Objects;

/**
 * Why a file, an export or a whole execution failed (§4.2). {@code rowNumber} is the physical row
 * (CSV/XLSX) and {@code lineNumber} the record ordinal, when known. The message never contains
 * credentials (INV-10).
 */
public record FileError(FileErrorType type, String message, Integer rowNumber, Integer lineNumber) {

    public FileError {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
    }

    public static FileError of(FileErrorType type, String message) {
        return new FileError(type, message, null, null);
    }
}
