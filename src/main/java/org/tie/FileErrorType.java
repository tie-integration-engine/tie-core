package org.tie;

/** Stable catalog of file and execution error types (§5.4, INV-20). */
public enum FileErrorType {
    /** Malformed CSV/XML/JSON, header that does not match in HEADER mode, undeclared repeated element or array. */
    STRUCTURAL,
    /** Bytes that are not valid in the declared encoding. */
    ENCODING,
    /** DOCTYPE or external entity in XML, XLSX above the compression limit. */
    UNSAFE_CONTENT,
    EXTENSION_NOT_ALLOWED,
    FILE_TOO_LARGE,
    DUPLICATE_TIMESTAMP,
    AMBIGUOUS_LAYOUT,
    /** Network, I/O or timeout error of a channel; a file that disappeared. */
    CHANNEL,
    AUTHENTICATION,
    HOST_KEY,
    NAME_COLLISION,
    THRESHOLD,
    CONSUMER,
    HOOK
}
