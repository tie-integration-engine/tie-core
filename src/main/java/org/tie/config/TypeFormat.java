package org.tie.config;

import java.util.Locale;

/** Format used to convert a field between text and its {@link FieldType}. */
public sealed interface TypeFormat {

    static TypeFormat date(String pattern) {
        return new DateFormat(pattern, null);
    }

    /** A date pattern whose month and day names and AM/PM are those of {@code locale} (null = {@link Locale#ROOT}). */
    static TypeFormat date(String pattern, Locale locale) {
        return new DateFormat(pattern, locale);
    }

    static TypeFormat decimal(Character decimalSeparator, Character groupingSeparator) {
        return new DecimalFormat(decimalSeparator, groupingSeparator);
    }

    static TypeFormat booleanLiterals(String trueLiteral, String falseLiteral) {
        return new BooleanFormat(trueLiteral, falseLiteral);
    }

    /**
     * {@link java.time.format.DateTimeFormatter} pattern for DATE and DATETIME, used to read and to write.
     * {@code locale} gives the text of month and day names and AM/PM; null means {@link Locale#ROOT}.
     */
    record DateFormat(String pattern, Locale locale) implements TypeFormat {
    }

    /** Separators for INTEGER and DECIMAL; a null grouping separator means none. */
    record DecimalFormat(Character decimalSeparator, Character groupingSeparator) implements TypeFormat {
    }

    /** Case-sensitive literals for BOOLEAN. */
    record BooleanFormat(String trueLiteral, String falseLiteral) implements TypeFormat {
    }
}
