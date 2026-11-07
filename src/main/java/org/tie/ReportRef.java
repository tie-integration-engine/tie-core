package org.tie;

/** A result report written for one input file and layout (§4.2). */
public record ReportRef(String sourceFileName, String layout, String fileName, String destination, ResultStatus status) {
}
