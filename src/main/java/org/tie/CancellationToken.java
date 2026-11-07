package org.tie;

/**
 * Asks a running import or export to stop (A8, A15). It is checked before each record; the record in
 * progress finishes. Thread-safe: call {@link #cancel()} from any thread.
 */
public final class CancellationToken {

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancellationRequested() {
        return cancelled;
    }
}
