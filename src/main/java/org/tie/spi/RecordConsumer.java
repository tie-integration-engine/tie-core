package org.tie.spi;

import org.tie.TypedRecord;

/**
 * Receives every valid record of an import, on the thread of the run, in file and line order, children
 * right after their parent (INV-14). The transaction belongs to the consumer: TIE never undoes what it
 * applied. An exception is handled by {@code consumerErrorPolicy} (A5).
 */
@FunctionalInterface
public interface RecordConsumer {

    Outcome accept(TypedRecord record);
}
