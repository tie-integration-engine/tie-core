package org.tie;

import java.util.Objects;

/** A non-fatal problem of an execution (§4.2). {@code fileName} is null when it concerns no file. */
public record Warning(WarningCode code, String message, String fileName) {

    public Warning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
