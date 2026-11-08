package org.tie.spi;

import org.tie.RecordView;

/** Resolves a {@code defaultValue} containing <code>${</code>; its answer becomes the field value. */
@FunctionalInterface
public interface DefaultValueResolver {

    String resolve(String template, RecordView recordSoFar);
}
