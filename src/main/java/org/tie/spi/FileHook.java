package org.tie.spi;

import org.tie.FileResult;
import org.tie.ReceivedFile;

/**
 * Hooks around each file that is opened (§5.3), from an input channel or provided by the caller. With a
 * hook, provided inputs are spooled to the working directory before they are read. An exception fails
 * the file with error type {@code HOOK} (A12).
 */
public interface FileHook {

    /** The file is fully available locally, before any record is read; called exactly once (INV-15). */
    default void onFileReceived(ReceivedFile file) {
    }

    /**
     * Every record was delivered; called before the post-success action, the report and the watermark
     * (INV-15); provided inputs have no post-success action nor watermark. It receives the provisional result: status SUCCESS with final counters.
     */
    default void onFileCompleted(FileResult partial) {
    }
}
