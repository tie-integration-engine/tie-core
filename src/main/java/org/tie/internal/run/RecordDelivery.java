package org.tie.internal.run;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.tie.CancellationToken;
import org.tie.ErrorCode;
import org.tie.FileErrorType;
import org.tie.LineError;
import org.tie.LineRejection;
import org.tie.config.ConsumerErrorPolicy;
import org.tie.config.Layout;
import org.tie.config.OnParentRejected;
import org.tie.config.RejectionThreshold;
import org.tie.internal.Exceptions;
import org.tie.internal.engine.FieldValues;
import org.tie.internal.engine.ImportEngine;
import org.tie.internal.engine.LineResult;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.NestedRecords;
import org.tie.internal.format.ReadRecord;
import org.tie.internal.format.RecordReader;
import org.tie.spi.Outcome;
import org.tie.spi.RecordConsumer;

/**
 * Delivers the records of one file (§3 step 5): numbers the lines (children right after their parent),
 * runs the field engine, hands valid records to the consumer, cascades rejected parents (A10) and
 * enforces the absolute rejection threshold (A6). Also runs the validation pass of
 * {@code VALIDATE_THEN_DELIVER}.
 */
final class RecordDelivery {

    /** Thrown when the cancellation token is set before a root record (A8). */
    static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Cancelled() {
            super(null, null, false, false);
        }
    }

    private final Map<String, ImportEngine> engines;
    private final RecordConsumer consumer;
    private final ConsumerErrorPolicy errorPolicy;
    private final Integer maxRejected;
    private final CancellationToken cancellation;
    private final String fileName;
    private final Consumer<LineRejection> rejections;

    private int line;
    private int linesRead;
    private int applied;
    private int rejected;
    private int deliveredUpToLine;

    RecordDelivery(Map<String, ImportEngine> engines, RecordConsumer consumer, ConsumerErrorPolicy errorPolicy,
                   Integer maxRejected, CancellationToken cancellation, String fileName,
                   Consumer<LineRejection> rejections) {
        this.engines = engines;
        this.consumer = consumer;
        this.errorPolicy = errorPolicy;
        this.maxRejected = maxRejected;
        this.cancellation = cancellation;
        this.fileName = fileName;
        this.rejections = rejections;
    }

    int linesRead() {
        return linesRead;
    }

    int applied() {
        return applied;
    }

    int rejected() {
        return rejected;
    }

    int deliveredUpToLine() {
        return deliveredUpToLine;
    }

    /** Next line number, for errors that happen while reading it. */
    int nextLine() {
        return line + 1;
    }

    /**
     * Delivers every record of the reader.
     *
     * @throws FileFailure on a read error, an exceeded threshold or a consumer abort
     * @throws Cancelled   if cancellation was requested before a root record
     */
    void deliver(RecordReader reader, Layout root) {
        ReadRecord record;
        while ((record = reader.next()) != null) {
            // checked before each record: a file whose last record was delivered is not cancelled
            if (cancellation != null && cancellation.isCancellationRequested()) {
                throw new Cancelled();
            }
            process(record, root, null, false);
        }
    }

    private void process(ReadRecord record, Layout layout, Integer parentLine, boolean parentRejected) {
        int lineNumber = ++line;
        linesRead++;
        boolean rejectedLine;
        if (parentRejected && layout.onParentRejected() == OnParentRejected.CASCADE) {
            reject(record, layout, lineNumber, parentLine, List.of(LineError.of(null, ErrorCode.PARENT_REJECTED,
                    "parentLineNumber", String.valueOf(parentLine))));
            rejectedLine = true;
        } else {
            rejectedLine = engineAndConsumer(record, layout, lineNumber, parentLine);
        }
        deliveredUpToLine = lineNumber;
        for (NestedRecords nested : record.children()) {
            for (ReadRecord child : nested.records()) {
                process(child, nested.layout(), lineNumber, rejectedLine);
            }
        }
    }

    /** @return whether the line was rejected */
    private boolean engineAndConsumer(ReadRecord record, Layout layout, int lineNumber, Integer parentLine) {
        LineResult result = engines.get(layout.name()).process(record.input());
        if (result instanceof LineResult.Rejected(List<LineError> errors, Map<String, String> ignored)) {
            reject(record, layout, lineNumber, parentLine, errors);
            return true;
        }
        FieldValues values = ((LineResult.Accepted) result).values();
        Map<String, Boolean> declared = new LinkedHashMap<>();
        for (NestedRecords nested : record.children()) {
            declared.put(nested.layout().name(), nested.declared());
        }
        DefaultTypedRecord typed = new DefaultTypedRecord(values, layout.name(), fileName, lineNumber, parentLine,
                declared);
        Outcome outcome;
        try {
            outcome = consumer.accept(typed);
            if (outcome == null) {
                throw new IllegalStateException("the consumer returned no outcome");
            }
        } catch (RuntimeException e) {
            String message = Exceptions.message(e);
            if (errorPolicy == ConsumerErrorPolicy.ABORT_FILE) {
                throw new FileFailure(FileErrorType.CONSUMER, "the consumer failed at line " + lineNumber + ": "
                        + message, record.rowNumber(), e);
            }
            reject(record, layout, lineNumber, parentLine,
                    List.of(LineError.of(null, ErrorCode.CONSUMER_FAILED, "exceptionMessage", message)));
            return true;
        }
        if (outcome instanceof Outcome.Rejected(String reason)) {
            reject(record, layout, lineNumber, parentLine,
                    List.of(LineError.of(null, ErrorCode.CONSUMER_REJECTED, "reason", reason)));
            return true;
        }
        applied++;
        return false;
    }

    private void reject(ReadRecord record, Layout layout, int lineNumber, Integer parentLine, List<LineError> errors) {
        rejected++;
        rejections.accept(new LineRejection(lineNumber, record.rowNumber(), layout.name(), errors, fileName, parentLine,
                record.input().rawValues()));
        if (maxRejected != null && rejected > maxRejected) {
            deliveredUpToLine = lineNumber;
            throw new FileFailure(FileErrorType.THRESHOLD, "rejection threshold exceeded: " + rejected
                    + " rejected lines, at most " + maxRejected + " allowed", record.rowNumber(), null);
        }
    }

    // ---------------------------------------------------------------- validation pass

    /**
     * The validation pass of {@code VALIDATE_THEN_DELIVER}: reads the whole file through the engine
     * without delivering. A structural error, or an engine rejection percentage above the threshold,
     * fails the file before any delivery (A6, A7).
     */
    static void validate(RecordReader reader, Layout root, Map<String, ImportEngine> engines,
                         RejectionThreshold threshold) {
        int[] counts = new int[2];
        ReadRecord record;
        while ((record = reader.next()) != null) {
            count(record, root, engines, counts, false);
        }
        BigDecimal percent = threshold.maxRejectedPercent();
        int total = counts[0];
        int rejected = counts[1];
        if (percent != null && total > 0
                && BigDecimal.valueOf(rejected).multiply(BigDecimal.valueOf(100))
                .compareTo(percent.multiply(BigDecimal.valueOf(total))) > 0) {
            throw new FileFailure(FileErrorType.THRESHOLD, "rejection threshold exceeded: " + rejected + " of "
                    + total + " lines rejected, at most " + percent.stripTrailingZeros().toPlainString() + "% allowed",
                    null, null);
        }
    }

    /** Counts lines and engine rejections; children cascaded from a rejected parent are not engine rejections. */
    private static void count(ReadRecord record, Layout layout, Map<String, ImportEngine> engines, int[] counts,
                              boolean parentRejected) {
        counts[0]++;
        boolean rejected = parentRejected && layout.onParentRejected() == OnParentRejected.CASCADE;
        if (!rejected && engines.get(layout.name()).process(record.input()) instanceof LineResult.Rejected) {
            counts[1]++;
            rejected = true;
        }
        for (NestedRecords nested : record.children()) {
            for (ReadRecord child : nested.records()) {
                count(child, nested.layout(), engines, counts, rejected);
            }
        }
    }
}
