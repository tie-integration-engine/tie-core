package org.tie.internal.pattern;

/** A name pattern with invalid syntax; the message is a configuration problem. */
public final class NamePatternException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public NamePatternException(String message) {
        super(message);
    }
}
