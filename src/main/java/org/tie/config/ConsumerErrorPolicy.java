package org.tie.config;

/** What an import does when the record consumer throws. */
public enum ConsumerErrorPolicy {
    REJECT_LINE,
    ABORT_FILE
}
