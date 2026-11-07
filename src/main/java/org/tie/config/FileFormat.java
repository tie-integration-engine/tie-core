package org.tie.config;

import java.util.Locale;
import java.util.Optional;

/** File formats TIE reads and writes. */
public enum FileFormat {
    CSV("csv"),
    XLSX("xlsx"),
    XML("xml"),
    JSON("json");

    private final String extension;

    FileFormat(String extension) {
        this.extension = extension;
    }

    /** Lower-case file extension without the dot. */
    public String extension() {
        return extension;
    }

    /** Whether rows of this format are located by column (CSV and XLSX). */
    public boolean isTabular() {
        return this == CSV || this == XLSX;
    }

    /** The format for a file extension, ignoring case ({@code "CSV"} is CSV). */
    public static Optional<FileFormat> fromExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        String lower = extension.toLowerCase(Locale.ROOT);
        for (FileFormat format : values()) {
            if (format.extension.equals(lower)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
