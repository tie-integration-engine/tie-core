package org.tie.internal;

/** Exception helpers. */
public final class Exceptions {

    private Exceptions() {
    }

    /** {@code getMessage()}, or the simple class name when it is null (§5.4 {@code exceptionMessage}). */
    public static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
