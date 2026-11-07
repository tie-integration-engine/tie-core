package org.tie.config;

/**
 * Optional result file written for each input file and layout. {@code namePattern} accepts
 * {@code {sourceName}}, {@code {layout}}, {@code {timestamp:…}} and variables; the extension comes
 * from {@code format}.
 */
public record ResultReport(ReportMode mode, FileFormat format, ChannelDefinition channel, String namePattern) {

    public static final ResultReport NONE = new ResultReport(ReportMode.NONE, null, null, null);

    public ResultReport {
        mode = mode == null ? ReportMode.NONE : mode;
    }
}
