package org.tie;

/** Stable catalog of warning codes (§5.4, INV-20). */
public enum WarningCode {
    HEADER_MISMATCH,
    EXTRA_COLUMNS,
    MISSING_COLUMN,
    UNSAFE_FILE_NAME,
    POST_ACTION_FAILED,
    REPORT_FAILED,
    LISTENER_FAILED,
    TEMP_CLEANUP_FAILED
}
