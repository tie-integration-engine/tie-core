package org.tie;

/** Status of one file of an import. */
public enum FileStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    /** Not reached because an earlier file failed or the run was cancelled. */
    NOT_PROCESSED
}
