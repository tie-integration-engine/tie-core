package org.tie.spi;

import org.tie.RemoteFile;

/** Tells whether a listed file was already processed; {@code true} excludes it from the run. */
@FunctionalInterface
public interface AlreadyProcessedPredicate {

    boolean test(RemoteFile file);
}
