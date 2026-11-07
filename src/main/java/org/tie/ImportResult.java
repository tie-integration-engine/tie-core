package org.tie;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of an import (§4.2). {@code watermarks} are the new marks the caller should store; {@code error}
 * is set when the run failed before any file (A14). {@code warnings} holds every warning of the run,
 * including those of each file.
 */
public record ImportResult(ResultStatus status, List<FileResult> files, Map<String, LocalDateTime> watermarks,
                           List<Warning> warnings, List<ReportRef> reports, FileError error) {

    public ImportResult {
        files = List.copyOf(files);
        watermarks = Collections.unmodifiableMap(new LinkedHashMap<>(watermarks));
        warnings = List.copyOf(warnings);
        reports = List.copyOf(reports);
    }
}
