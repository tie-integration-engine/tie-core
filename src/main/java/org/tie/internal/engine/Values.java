package org.tie.internal.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.tie.config.FieldType;
import org.tie.config.TypeFormat;

/** Conversions between typed values and text for export (§4.4) and for the writers. */
public final class Values {

    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private Values() {
    }

    /** The canonical text of a typed value: plain numbers, ISO dates, {@code true}/{@code false}; "" for null. */
    public static String canonical(Object value) {
        return switch (value) {
            case null -> "";
            case BigDecimal decimal -> decimal.toPlainString();
            case LocalDateTime dateTime -> ISO_DATE_TIME.format(dateTime);
            default -> value.toString();
        };
    }

    /**
     * The value of an exported record as the Java type of {@code type}: numbers of any kind, the canonical
     * text, or the type itself. Empty if it cannot be represented.
     */
    static Optional<Object> coerce(Object value, FieldType type) {
        Object coerced = switch (type) {
            case STRING -> value.toString();
            case INTEGER -> switch (value) {
                case Long l -> l;
                case Integer i -> (long) i;
                case Short s -> (long) s;
                case Byte b -> (long) b;
                case BigInteger big -> big.bitLength() < 64 ? big.longValue() : null;
                case BigDecimal decimal -> integral(decimal);
                case String text -> parse(text, type);
                default -> null;
            };
            case DECIMAL -> switch (value) {
                case BigDecimal decimal -> decimal;
                case BigInteger big -> new BigDecimal(big);
                case Long l -> BigDecimal.valueOf(l);
                case Integer i -> BigDecimal.valueOf(i);
                case Short s -> BigDecimal.valueOf(s);
                case Byte b -> BigDecimal.valueOf(b);
                case Double d -> Double.isFinite(d) ? new BigDecimal(Double.toString(d)) : null;
                case Float f -> Float.isFinite(f) ? new BigDecimal(Float.toString(f)) : null;
                case String text -> parse(text, type);
                default -> null;
            };
            case DATE -> value instanceof LocalDate ? value : value instanceof String text ? parse(text, type) : null;
            case DATETIME -> value instanceof LocalDateTime ? value : value instanceof String text ? parse(text, type) : null;
            case BOOLEAN -> value instanceof Boolean ? value : value instanceof String text ? parse(text, type) : null;
        };
        return Optional.ofNullable(coerced);
    }

    private static Long integral(BigDecimal decimal) {
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Object parse(String text, FieldType type) {
        return new TypeConverter(type, null).convert(text.strip(), false).orElse(null);
    }

    /** The text of a typed value with the field's {@code typeFormat} (§4.4 step 4). */
    static String format(Object value, TypeFormat format) {
        return switch (format) {
            case null -> canonical(value);
            case TypeFormat.DateFormat(String pattern, Locale locale) -> TypeConverter.strict(pattern, locale).format(
                    (java.time.temporal.TemporalAccessor) value);
            case TypeFormat.DecimalFormat(Character decimal, Character grouping) -> number(value, decimal, grouping);
            case TypeFormat.BooleanFormat(String trueLiteral, String falseLiteral) ->
                    Boolean.TRUE.equals(value) ? trueLiteral : falseLiteral;
        };
    }

    private static String number(Object value, Character decimalSeparator, Character grouping) {
        String plain = canonical(value);
        String sign = plain.startsWith("-") ? "-" : "";
        String unsigned = plain.substring(sign.length());
        int dot = unsigned.indexOf('.');
        String integer = dot < 0 ? unsigned : unsigned.substring(0, dot);
        String fraction = dot < 0 ? "" : unsigned.substring(dot + 1);
        if (grouping != null) {
            StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < integer.length(); i++) {
                if (i > 0 && (integer.length() - i) % 3 == 0) {
                    grouped.append(grouping);
                }
                grouped.append(integer.charAt(i));
            }
            integer = grouped.toString();
        }
        char separator = decimalSeparator == null ? '.' : decimalSeparator;
        return sign + integer + (fraction.isEmpty() ? "" : separator + fraction);
    }
}
