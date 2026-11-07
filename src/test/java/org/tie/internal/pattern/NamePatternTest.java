package org.tie.internal.pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NamePatternTest {

    private static final Map<String, String> NO_VARIABLES = Map.of();

    private static LocalDateTime match(String pattern, String baseName) {
        return NamePattern.parse(pattern).match(baseName, Map.of("tenant", "ACME")).orElseThrow().timestamp();
    }

    private static boolean matches(String pattern, String baseName, Map<String, String> variables) {
        return NamePattern.parse(pattern).match(baseName, variables).isPresent();
    }

    @Nested
    class Matching {

        @Test
        void variablesAndTimestamp() {
            assertThat(match("alta_{tenant}_{timestamp:yyyyMMddHHmmss}", "alta_ACME_20261026120000"))
                    .isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 0, 0));
        }

        @Test
        void dateOnlyTimestampIsStartOfDay() {
            assertThat(match("alta_{timestamp:yyyyMMdd}", "alta_20261026"))
                    .isEqualTo(LocalDateTime.of(2026, 10, 26, 0, 0, 0));
        }

        @Test
        void separatorsQuotesAndFractions() {
            assertThat(match("x_{timestamp:yyyy-MM-dd'T'HH.mm.ss.SSS}", "x_2026-10-26T12.30.05.250"))
                    .isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 30, 5, 250_000_000));
            assertThat(match("x_{timestamp:yyyy-M-d}", "x_2026-1-5")).isEqualTo(LocalDateTime.of(2026, 1, 5, 0, 0));
            assertThat(match("x_{timestamp:yyyy-M-d}", "x_2026-10-26")).isEqualTo(LocalDateTime.of(2026, 10, 26, 0, 0));
            assertThat(match("x_{timestamp:yyMMdd}", "x_261026")).isEqualTo(LocalDateTime.of(2026, 10, 26, 0, 0));
        }

        @Test
        void withoutTimestampTheMatchHasNone() {
            assertThat(NamePattern.parse("alta_{tenant}").match("alta_ACME", Map.of("tenant", "ACME")).orElseThrow()
                    .timestamp()).isNull();
        }

        @ParameterizedTest(name = "{1}")
        @CsvSource({
                "alta_{timestamp:yyyyMMdd}, alta_20261026_x",
                "alta_{timestamp:yyyyMMdd}, x_alta_20261026",
                "alta_{timestamp:yyyyMMdd}, alta_2026102",
                "alta_{timestamp:yyyyMMdd}, alta_20261301",
                "alta_{timestamp:yyyyMMdd}, alta_20260230",
                "alta_{timestamp:yyyyMMdd}, ALTA_20261026",
                "alta_{tenant}, alta_OTHER",
        })
        void wholeNameMustMatchExactly(String pattern, String baseName) {
            assertThat(matches(pattern, baseName, Map.of("tenant", "ACME"))).isFalse();
        }

        @Test
        void literalsAndVariableValuesAreNotRegexes() {
            assertThat(matches("a.b+c", "a.b+c", NO_VARIABLES)).isTrue();
            assertThat(matches("a.b+c", "axbbc", NO_VARIABLES)).isFalse();
            assertThat(matches("x_{v}", "x_a.*", Map.of("v", "a.*"))).isTrue();
            assertThat(matches("x_{v}", "x_abc", Map.of("v", "a.*"))).isFalse();
        }

        @Test
        void missingVariableIsAnError() {
            NamePattern pattern = NamePattern.parse("alta_{tenant}");

            assertThatThrownBy(() -> pattern.match("alta_ACME", NO_VARIABLES))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("missing variable 'tenant'");
        }
    }

    @Nested
    class Rendering {

        @Test
        void rendersVariablesAndTimestamp() {
            NamePattern pattern = NamePattern.parse("out_{tenant}_{timestamp:yyyyMMddHHmmss}");

            assertThat(pattern.render(Map.of("tenant", "ACME"), LocalDateTime.of(2026, 10, 26, 12, 0)))
                    .isEqualTo("out_ACME_20261026120000");
        }

        @Test
        void renderedNamesMatchTheirPattern() {
            NamePattern pattern = NamePattern.parse("r_{timestamp:yyyy-MM-dd'T'HHmmss}");
            LocalDateTime now = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

            assertThat(pattern.match(pattern.render(NO_VARIABLES, now), NO_VARIABLES).orElseThrow().timestamp())
                    .isEqualTo(now);
        }
    }

    @Nested
    class Syntax {

        @Test
        void exposesVariablesInOrder() {
            NamePattern pattern = NamePattern.parse("{b}_{a}_{timestamp:yyyyMMdd}_{b}");

            assertThat(pattern.variables()).containsExactly("b", "a");
            assertThat(pattern.hasTimestamp()).isTrue();
            assertThat(NamePattern.parse("plain").hasTimestamp()).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "''| pattern is empty",
                "a/b| pattern 'a/b' must not contain path separators",
                "a\\b| pattern 'a\\b' must not contain path separators",
                "a_{x| pattern 'a_{x' has an unclosed '{' at position 2",
                "a}| pattern 'a}' has an unmatched '}' at position 1",
                "{}| pattern '{}' has an invalid marker {}",
                "{1x}| pattern '{1x}' has an invalid marker {1x}",
                "{timestamp}| pattern '{timestamp}' has an invalid marker {timestamp}",
                "{timestamp:}| pattern '{timestamp:}' has an empty {timestamp:}",
                "{timestamp:yyyyMMdd}{timestamp:HH}| pattern '{timestamp:yyyyMMdd}{timestamp:HH}' has more than one {timestamp:…}",
                "{timestamp:HHmm}| pattern '{timestamp:HHmm}' has an invalid timestamp format 'HHmm': year, month and day are required",
                "{timestamp:yyyyMMddEEE}| pattern '{timestamp:yyyyMMddEEE}' has an invalid timestamp format 'yyyyMMddEEE': letter 'E' is not supported",
                "{timestamp:yyyMMdd}| pattern '{timestamp:yyyMMdd}' has an invalid timestamp format 'yyyMMdd': year must be yy or yyyy",
                "{timestamp:yyyyMMMdd}| pattern '{timestamp:yyyyMMMdd}' has an invalid timestamp format 'yyyyMMMdd': 'M' must be 1 or 2 letters",
                "{timestamp:yyyy'MMdd}| pattern '{timestamp:yyyy'MMdd}' has an invalid timestamp format 'yyyy'MMdd': unclosed quote",
        })
        void rejectsInvalidSyntax(String pattern, String message) {
            assertThatThrownBy(() -> NamePattern.parse(pattern))
                    .isInstanceOf(NamePatternException.class)
                    .hasMessage(message);
        }
    }
}
