package org.tie.config;

/**
 * How the resolved default value of a field is delivered on import (§4.3 step 9). Ignored on export.
 */
public enum DefaultMode {
    /** Converted to the field's type like any other value (the default, the 0.2.0 behaviour). */
    CONVERT,
    /**
     * Delivered as the resolved text, a {@code String} whatever the field's type, without conversion; the
     * field's validations still apply to that text (INV-24).
     */
    TEXT
}
