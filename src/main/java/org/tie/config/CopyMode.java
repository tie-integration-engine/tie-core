package org.tie.config;

/**
 * What a field with {@code copyFrom} receives from its source on import (§4.3 "Copia"). Ignored on export,
 * where a copy always takes the source's supplied value.
 */
public enum CopyMode {
    /** The source's text after its steps 3-6, before conversion to its type (the default). */
    TEXT,
    /**
     * The canonical text of the source's typed value ({@code yyyy-MM-dd}, plain decimals, {@code true}/{@code false}),
     * whatever the source's {@code typeFormat}.
     */
    CANONICAL
}
