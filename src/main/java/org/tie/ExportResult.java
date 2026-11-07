package org.tie;

import java.util.List;

/**
 * Result of an export (§4.2). {@code fileName} and {@code destination} are set when the file was
 * delivered; {@code fileName} carries the collision suffix if one was added.
 *
 * @param written records written, children included
 */
public record ExportResult(ResultStatus status, int written, List<LineRejection> rejected, String fileName,
                           String destination, FileError error, List<Warning> warnings) {

    public ExportResult {
        rejected = List.copyOf(rejected);
        warnings = List.copyOf(warnings);
    }
}
