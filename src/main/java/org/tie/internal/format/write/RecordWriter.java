package org.tie.internal.format.write;

/** Writes the records of one file incrementally (INV-13). */
public interface RecordWriter extends AutoCloseable {

    void write(OutputRecord record);

    /** Completes the file (closing tags, header-only CSV…); never called after a failure. */
    void finish();

    /** Releases resources; the file may be incomplete if {@link #finish()} was not called. */
    @Override
    void close();
}
