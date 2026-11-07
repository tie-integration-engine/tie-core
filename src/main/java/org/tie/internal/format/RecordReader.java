package org.tie.internal.format;

/** Records of one file, read incrementally (INV-13). */
public interface RecordReader extends AutoCloseable {

    /**
     * The next root record, or null at the end of the file.
     *
     * @throws FileFailure if the file cannot be read further; records returned before stay valid
     */
    ReadRecord next();

    @Override
    void close();
}
