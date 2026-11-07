package org.tie;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of one file of an import (§4.2).
 *
 * @param linesRead         records read, children included
 * @param deliveredUpToLine last line processed through the consumer (0 if none)
 * @param timestamp         the name's timestamp per its pattern; null for provided inputs and patterns
 *                          without timestamp
 */
public record FileResult(String name, String layout, FileStatus status, int linesRead, int applied, int rejected,
                         int deliveredUpToLine, FileError error, List<Warning> warnings, LocalDateTime timestamp) {

    public FileResult {
        warnings = List.copyOf(warnings);
    }
}
