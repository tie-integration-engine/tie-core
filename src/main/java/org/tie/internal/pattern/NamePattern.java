package org.tie.internal.pattern;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file name pattern (§4.1, "Patrones de nombre"): literal text plus {@code {variable}} and at most
 * one {@code {timestamp:<pattern>}}. No wildcards, no extension, no path separators.
 *
 * <p>Timestamp patterns are restricted to numeric fields so they can be matched by width:
 * {@code yyyy}/{@code uuuu}, {@code yy}, {@code M}/{@code MM}, {@code d}/{@code dd}, {@code H}/{@code HH},
 * {@code m}/{@code mm}, {@code s}/{@code ss}, {@code S…}, quoted text and non-letter literals. Year,
 * month and day are required; missing time fields are 0.
 */
public final class NamePattern {

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final String TIMESTAMP_PREFIX = "timestamp:";
    private static final String RESERVED_DATE_TIME_CHARS = "[]{}#";

    private final String source;
    private final List<Segment> segments;
    private final Timestamp timestamp;

    private NamePattern(String source, List<Segment> segments, Timestamp timestamp) {
        this.source = source;
        this.segments = segments;
        this.timestamp = timestamp;
    }

    /**
     * Parses a pattern.
     *
     * @throws NamePatternException with a message suitable for a configuration problem
     */
    public static NamePattern parse(String source) {
        if (source == null || source.isEmpty()) {
            throw new NamePatternException("pattern is empty");
        }
        if (source.contains("/") || source.contains("\\")) {
            throw new NamePatternException("pattern '" + source + "' must not contain path separators");
        }
        List<Segment> segments = new ArrayList<>();
        Timestamp timestamp = null;
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '}') {
                throw new NamePatternException("pattern '" + source + "' has an unmatched '}' at position " + i);
            }
            if (c != '{') {
                literal.append(c);
                i++;
                continue;
            }
            int close = source.indexOf('}', i);
            if (close < 0) {
                throw new NamePatternException("pattern '" + source + "' has an unclosed '{' at position " + i);
            }
            if (!literal.isEmpty()) {
                segments.add(new Literal(literal.toString()));
                literal.setLength(0);
            }
            String marker = source.substring(i + 1, close);
            if (marker.startsWith(TIMESTAMP_PREFIX)) {
                if (timestamp != null) {
                    throw new NamePatternException("pattern '" + source + "' has more than one {timestamp:…}");
                }
                timestamp = Timestamp.parse(source, marker.substring(TIMESTAMP_PREFIX.length()));
                segments.add(timestamp);
            } else {
                if (!VARIABLE_NAME.matcher(marker).matches() || marker.equals("timestamp")) {
                    throw new NamePatternException("pattern '" + source + "' has an invalid marker {" + marker + "}");
                }
                segments.add(new Variable(marker));
            }
            i = close + 1;
        }
        if (!literal.isEmpty()) {
            segments.add(new Literal(literal.toString()));
        }
        return new NamePattern(source, List.copyOf(segments), timestamp);
    }

    public String source() {
        return source;
    }

    public boolean hasTimestamp() {
        return timestamp != null;
    }

    /** Variable names in order of appearance, without {@code timestamp}. */
    public Set<String> variables() {
        Set<String> names = new LinkedHashSet<>();
        for (Segment segment : segments) {
            if (segment instanceof Variable variable) {
                names.add(variable.name());
            }
        }
        return names;
    }

    /**
     * Matches a whole base name (the file name without its last extension).
     *
     * @return empty if it does not match; otherwise the match, with the timestamp if the pattern has one
     * @throws IllegalArgumentException if a variable of the pattern is missing from {@code variables}
     */
    public Optional<Match> match(String baseName, Map<String, String> variables) {
        StringBuilder regex = new StringBuilder("^");
        for (Segment segment : segments) {
            switch (segment) {
                case Literal literal -> regex.append(Pattern.quote(literal.text()));
                case Variable variable -> regex.append(Pattern.quote(require(variables, variable.name())));
                case Timestamp ts -> regex.append("(?<ts>").append(ts.regex()).append(')');
            }
        }
        Matcher matcher = Pattern.compile(regex.append('$').toString()).matcher(baseName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        if (timestamp == null) {
            return Optional.of(new Match(null));
        }
        try {
            return Optional.of(new Match(LocalDateTime.parse(matcher.group("ts"), timestamp.formatter())));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Renders an output name (without extension).
     *
     * @throws IllegalArgumentException if a variable of the pattern is missing from {@code variables}
     */
    public String render(Map<String, String> variables, LocalDateTime now) {
        StringBuilder name = new StringBuilder();
        for (Segment segment : segments) {
            switch (segment) {
                case Literal literal -> name.append(literal.text());
                case Variable variable -> name.append(require(variables, variable.name()));
                case Timestamp ts -> name.append(ts.formatter().format(now));
            }
        }
        return name.toString();
    }

    @Override
    public String toString() {
        return source;
    }

    private static String require(Map<String, String> variables, String name) {
        String value = variables.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing variable '" + name + "'");
        }
        return value;
    }

    /** Result of a successful match; {@code timestamp} is null when the pattern has none. */
    public record Match(LocalDateTime timestamp) {
    }

    private sealed interface Segment permits Literal, Variable, Timestamp {
    }

    private record Literal(String text) implements Segment {
    }

    private record Variable(String name) implements Segment {
    }

    private record Timestamp(String regex, DateTimeFormatter formatter) implements Segment {

        static Timestamp parse(String source, String pattern) {
            if (pattern.isEmpty()) {
                throw new NamePatternException("pattern '" + source + "' has an empty {timestamp:}");
            }
            StringBuilder regex = new StringBuilder();
            StringBuilder strictPattern = new StringBuilder();
            boolean year = false;
            boolean month = false;
            boolean day = false;
            int i = 0;
            while (i < pattern.length()) {
                char c = pattern.charAt(i);
                if (c == '\'') {
                    int close = pattern.indexOf('\'', i + 1);
                    if (close < 0) {
                        throw invalid(source, pattern, "unclosed quote");
                    }
                    String text = pattern.substring(i + 1, close);
                    if (text.isEmpty()) {
                        throw invalid(source, pattern, "'' is not supported");
                    }
                    regex.append(Pattern.quote(text));
                    strictPattern.append(pattern, i, close + 1);
                    i = close + 1;
                    continue;
                }
                if (!Character.isLetter(c)) {
                    if (RESERVED_DATE_TIME_CHARS.indexOf(c) >= 0) {
                        throw invalid(source, pattern, "'" + c + "' is not supported");
                    }
                    regex.append(Pattern.quote(String.valueOf(c)));
                    strictPattern.append('\'').append(c).append('\'');
                    i++;
                    continue;
                }
                int end = i;
                while (end < pattern.length() && pattern.charAt(end) == c) {
                    end++;
                }
                int width = end - i;
                switch (c) {
                    case 'y', 'u' -> {
                        if (width != 2 && width != 4) {
                            throw invalid(source, pattern, "year must be yy or yyyy");
                        }
                        year = true;
                        regex.append("\\d{").append(width).append('}');
                        strictPattern.append("u".repeat(width));
                    }
                    case 'M', 'd', 'H', 'm', 's' -> {
                        if (width > 2) {
                            throw invalid(source, pattern, "'" + c + "' must be 1 or 2 letters");
                        }
                        month |= c == 'M';
                        day |= c == 'd';
                        regex.append(width == 1 ? "\\d{1,2}" : "\\d{2}");
                        strictPattern.append(String.valueOf(c).repeat(width));
                    }
                    case 'S' -> {
                        if (width > 9) {
                            throw invalid(source, pattern, "fraction must be at most 9 letters");
                        }
                        regex.append("\\d{").append(width).append('}');
                        strictPattern.append("S".repeat(width));
                    }
                    default -> throw invalid(source, pattern, "letter '" + c + "' is not supported");
                }
                i = end;
            }
            if (!year || !month || !day) {
                throw invalid(source, pattern, "year, month and day are required");
            }
            DateTimeFormatter formatter;
            try {
                formatter = new DateTimeFormatterBuilder()
                        .appendPattern(strictPattern.toString())
                        .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                        .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                        .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                        .toFormatter()
                        .withResolverStyle(ResolverStyle.STRICT);
            } catch (IllegalArgumentException e) {
                throw invalid(source, pattern, e.getMessage());
            }
            return new Timestamp(regex.toString(), formatter);
        }

        private static NamePatternException invalid(String source, String pattern, String reason) {
            return new NamePatternException(
                    "pattern '" + source + "' has an invalid timestamp format '" + pattern + "': " + reason);
        }
    }
}
