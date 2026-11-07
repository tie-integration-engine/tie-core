package org.tie;

/** Base class of the exceptions TIE throws. Failures during a run are reported in results instead. */
public class TieException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TieException(String message) {
        super(message);
    }

    public TieException(String message, Throwable cause) {
        super(message, cause);
    }
}
