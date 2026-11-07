package org.tie.internal.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tie.LineError;

/** One line of a result report: its raw values, its result and, if rejected, its errors. */
public record ReportLine(int lineNumber, String result, Map<String, String> values, List<Error> errors) {

    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";

    public ReportLine {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        errors = List.copyOf(errors);
    }

    public static ReportLine rejected(int lineNumber, Map<String, String> values, List<LineError> errors) {
        return new ReportLine(lineNumber, REJECTED, values, errors.stream()
                .map(e -> new Error(e.field(), e.code().name(), e.args(), e.message())).toList());
    }

    public static ReportLine applied(int lineNumber, Map<String, String> values) {
        return new ReportLine(lineNumber, APPLIED, values, List.of());
    }

    /** {@code <field>: <message>} joined with {@code " | "} (§4.1 "Informe de resultado"). */
    public String reason() {
        return String.join(" | ", errors.stream()
                .map(e -> e.field() == null ? e.message() : e.field() + ": " + e.message()).toList());
    }

    public String codes() {
        return String.join(",", errors.stream().map(Error::code).toList());
    }

    public record Error(String field, String code, Map<String, String> args, String message) {
        public Error {
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }
    }
}
