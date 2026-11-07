package org.tie.internal.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.tie.config.FieldType;
import org.tie.config.TypeFormat;

/**
 * Converts the text of a field to its {@link FieldType} (§4.3 step 7) with the defaults of §4.1
 * ("Tipos"). Dates are parsed strictly ({@code 31/02/2026} is invalid).
 */
final class TypeConverter {

    static final DateTimeFormatter ISO_DATE = strict("uuuu-MM-dd");
    static final DateTimeFormatter ISO_DATE_TIME = strict("uuuu-MM-dd'T'HH:mm:ss");

    private static final Pattern CANONICAL_INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern CANONICAL_DECIMAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    private final FieldType type;
    private final TypeFormat format;
    private final DateTimeFormatter dateFormatter;
    private final String formatDescription;
    private final Pattern groupedDigits;

    TypeConverter(FieldType type, TypeFormat format) {
        this.type = type;
        this.format = format;
        this.dateFormatter = format instanceof TypeFormat.DateFormat(String pattern) ? strict(pattern) : null;
        this.formatDescription = describe(format);
        this.groupedDigits = format instanceof TypeFormat.DecimalFormat(Character ignored, Character grouping)
                && grouping != null ? Pattern.compile("\\d{1,3}(" + Pattern.quote(grouping.toString()) + "\\d{3})+") : null;
    }

    FieldType type() {
        return type;
    }

    /** Text form of {@code typeFormat} for the {@code format} argument of INVALID_TYPE; null if none. */
    String formatDescription() {
        return formatDescription;
    }

    /**
     * Converts a non-empty value.
     *
     * @param canonicalFallback also accept the ISO/canonical form (values read from XLSX cells)
     * @return the typed value, or empty if the text is not valid for the type
     */
    Optional<Object> convert(String text, boolean canonicalFallback) {
        Object value = switch (type) {
            case STRING -> text;
            case INTEGER -> integer(text);
            case DECIMAL -> {
                BigDecimal parsed = decimal(text);
                yield parsed == null && canonicalFallback && format != null ? canonicalDecimal(text) : parsed;
            }
            case DATE -> {
                LocalDate parsed = date(text, dateFormatter == null ? ISO_DATE : dateFormatter);
                yield parsed == null && canonicalFallback && dateFormatter != null ? date(text, ISO_DATE) : parsed;
            }
            case DATETIME -> {
                LocalDateTime parsed = dateTime(text, dateFormatter == null ? ISO_DATE_TIME : dateFormatter);
                yield parsed == null && canonicalFallback && dateFormatter != null
                        ? dateTime(text, ISO_DATE_TIME) : parsed;
            }
            case BOOLEAN -> bool(text);
        };
        return Optional.ofNullable(value);
    }

    private Long integer(String text) {
        String digits = removeGrouping(text);
        if (digits == null || !CANONICAL_INTEGER.matcher(digits).matches()) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimal(String text) {
        char decimalSeparator = '.';
        if (format instanceof TypeFormat.DecimalFormat(Character decimal, Character ignored) && decimal != null) {
            decimalSeparator = decimal;
        }
        int separator = text.indexOf(decimalSeparator);
        if (separator >= 0 && text.indexOf(decimalSeparator, separator + 1) >= 0) {
            return null;
        }
        String integerPart = removeGrouping(separator < 0 ? text : text.substring(0, separator));
        if (integerPart == null) {
            return null;
        }
        String normalized = separator < 0 ? integerPart : integerPart + "." + text.substring(separator + 1);
        return canonicalDecimal(normalized);
    }

    private static BigDecimal canonicalDecimal(String text) {
        return CANONICAL_DECIMAL.matcher(text).matches() ? new BigDecimal(text) : null;
    }

    /**
     * Removes the grouping separator, if configured. When present it must group by thousands
     * ({@code 1.234.567}); otherwise the text is not a number (null), so that {@code 1234.5} with a
     * {@code .} grouping separator is never read as {@code 12345}.
     */
    private String removeGrouping(String text) {
        if (!(format instanceof TypeFormat.DecimalFormat(Character ignored, Character grouping)) || grouping == null) {
            return text;
        }
        String g = String.valueOf(grouping);
        String sign = text.startsWith("-") || text.startsWith("+") ? text.substring(0, 1) : "";
        String unsigned = text.substring(sign.length());
        if (!unsigned.contains(g)) {
            return text;
        }
        if (!groupedDigits.matcher(unsigned).matches()) {
            return null;
        }
        return sign + unsigned.replace(g, "");
    }

    private static LocalDate date(String text, DateTimeFormatter formatter) {
        try {
            return LocalDate.parse(text, formatter);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(String text, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(text, formatter);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Boolean bool(String text) {
        if (format instanceof TypeFormat.BooleanFormat(String trueLiteral, String falseLiteral)) {
            if (text.equals(trueLiteral)) {
                return true;
            }
            return text.equals(falseLiteral) ? false : null;
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }

    private static String describe(TypeFormat format) {
        return switch (format) {
            case null -> null;
            case TypeFormat.DateFormat(String pattern) -> pattern;
            case TypeFormat.DecimalFormat(Character decimal, Character grouping) ->
                    "decimalSeparator=" + (decimal == null ? "." : decimal)
                            + (grouping == null ? "" : ", groupingSeparator=" + grouping);
            case TypeFormat.BooleanFormat(String trueLiteral, String falseLiteral) -> trueLiteral + "/" + falseLiteral;
        };
    }

    /**
     * A strict formatter for a {@link DateTimeFormatter} pattern. {@code y} (year of era) is read as
     * {@code u} (proleptic year) so that STRICT resolution does not require an era.
     */
    static DateTimeFormatter strict(String pattern) {
        StringBuilder converted = new StringBuilder(pattern.length());
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            converted.append(!quoted && c == 'y' ? 'u' : c);
        }
        return DateTimeFormatter.ofPattern(converted.toString(), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }
}
