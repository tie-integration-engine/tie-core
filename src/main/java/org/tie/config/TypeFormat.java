package org.tie.config;

/** Format used to convert a field between text and its {@link FieldType}. */
public sealed interface TypeFormat {

    static TypeFormat date(String pattern) {
        return new DateFormat(pattern);
    }

    static TypeFormat decimal(Character decimalSeparator, Character groupingSeparator) {
        return new DecimalFormat(decimalSeparator, groupingSeparator);
    }

    static TypeFormat booleanLiterals(String trueLiteral, String falseLiteral) {
        return new BooleanFormat(trueLiteral, falseLiteral);
    }

    /** {@link java.time.format.DateTimeFormatter} pattern for DATE and DATETIME. */
    record DateFormat(String pattern) implements TypeFormat {
    }

    /** Separators for INTEGER and DECIMAL; a null grouping separator means none. */
    record DecimalFormat(Character decimalSeparator, Character groupingSeparator) implements TypeFormat {
    }

    /** Case-sensitive literals for BOOLEAN. */
    record BooleanFormat(String trueLiteral, String falseLiteral) implements TypeFormat {
    }
}
