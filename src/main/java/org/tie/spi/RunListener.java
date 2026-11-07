package org.tie.spi;

import org.tie.FileResult;
import org.tie.LineRejection;
import org.tie.Warning;

/** Events of a run, on its thread. Exceptions thrown by {@link #onFileFinished} become a warning. */
public interface RunListener {

    default void onFileStarted(String fileName, String layout) {
    }

    default void onLineRejected(LineRejection rejection) {
    }

    /** The final result of every file, after its post-success action and report. */
    default void onFileFinished(FileResult result) {
    }

    default void onWarning(Warning warning) {
    }
}
