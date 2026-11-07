package org.tie.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tie.ErrorCode;
import org.tie.LineError;
import org.tie.RecordView;
import org.tie.config.Direction;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.Layout;
import org.tie.config.OnEmpty;
import org.tie.config.OnUnmatched;
import org.tie.config.TextTransform;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.TypeFormat;
import org.tie.config.Validations;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.FieldTransformer;

class ImportEngineTest {

    private final Map<String, FieldTransformer> transformers = new HashMap<>();
    private DefaultValueResolver resolver;

    private ImportEngine engine(FieldDefinition.Builder... fields) {
        Layout.Builder layout = Layout.builder("l");
        for (FieldDefinition.Builder field : fields) {
            layout.field(field);
        }
        return new ImportEngine(layout.build(), transformers, resolver);
    }

    private static Map<String, String> line(String... namesAndValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            values.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return values;
    }

    private static FieldValues accepted(LineResult result) {
        assertThat(result).isInstanceOf(LineResult.Accepted.class);
        return ((LineResult.Accepted) result).values();
    }

    private static List<LineError> rejected(LineResult result) {
        assertThat(result).isInstanceOf(LineResult.Rejected.class);
        return ((LineResult.Rejected) result).errors();
    }

    /** Value of {@code field} after processing a line where it reads {@code input}. */
    private Object value(FieldDefinition.Builder field, String input) {
        String name = field.build().name();
        return accepted(engine(field).process(LineInput.of(line(name, input)))).get(name);
    }

    private LineError error(FieldDefinition.Builder field, String input) {
        String name = field.build().name();
        List<LineError> errors = rejected(engine(field).process(LineInput.of(line(name, input))));
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    @Nested
    class CopiesTrimAndText {

        @Test
        @DisplayName("AC-41 copies resolve in topological order whatever the declaration order")
        void copyChain() {
            FieldValues values = accepted(engine(
                    FieldDefinition.builder("C").copyFrom("B"),
                    FieldDefinition.builder("B").copyFrom("A"),
                    FieldDefinition.builder("A")).process(LineInput.of(line("A", "x"))));

            assertThat(values.asMap()).containsExactly(entry("C", "x"), entry("B", "x"), entry("A", "x"));
        }

        @Test
        @DisplayName("AC-42 trimming happens before translating")
        void trimBeforeTranslate() {
            assertThat(value(FieldDefinition.builder("sex").translation("M", "Male"), " M ")).isEqualTo("Male");
        }

        @Test
        @DisplayName("AC-43 translations are case-sensitive")
        void translationsAreCaseSensitive() {
            FieldDefinition.Builder field = FieldDefinition.builder("sex").translation("m", "Male");

            assertThat(error(field, "M").code()).isEqualTo(ErrorCode.NO_TRANSLATION);
            assertThat(value(field.onUnmatched(OnUnmatched.PASS), "M")).isEqualTo("M");
        }

        @Test
        @DisplayName("AC-44 an unmatched value with PASS / EMPTY / ERROR")
        void unmatched() {
            FieldDefinition.Builder field = FieldDefinition.builder("sex").translation("M", "Male");

            assertThat(value(field.onUnmatched(OnUnmatched.PASS), "X")).isEqualTo("X");
            assertThat(value(field.onUnmatched(OnUnmatched.EMPTY), "X")).isNull();
            assertThat(value(field.onUnmatched(OnUnmatched.EMPTY).defaultValue("U"), "X")).isEqualTo("U");
            assertThat(error(field.onUnmatched(OnUnmatched.ERROR).defaultValue(null), "X").message())
                    .isEqualTo("value 'X' has no translation");
        }

        @Test
        @DisplayName("AC-45 a translation to null empties the value, which onEmpty resolves")
        void translationToNull() {
            FieldDefinition.Builder field = FieldDefinition.builder("sex").translation("X", null);

            assertThat(value(field, "X")).isNull();
            assertThat(value(field.defaultValue("U"), "X")).isEqualTo("U");
        }

        @Test
        @DisplayName("AC-46 UPPER then STRIP_ACCENTS")
        void upperAndStripAccents() {
            assertThat(value(FieldDefinition.builder("n").textTransform(TextTransform.UPPER)
                    .textTransform(TextTransform.STRIP_ACCENTS), "josé")).isEqualTo("JOSE");
        }

        @Test
        @DisplayName("AC-47 PAD_LEFT pads and never truncates")
        void padLeft() {
            FieldDefinition.Builder field = FieldDefinition.builder("n").textTransform(TextTransform.padLeft(5, '0'));

            assertThat(value(field, "42")).isEqualTo("00042");
            assertThat(value(field, "123456")).isEqualTo("123456");
            assertThat(value(FieldDefinition.builder("n").textTransform(TextTransform.padRight(4, '_')), "ab"))
                    .isEqualTo("ab__");
        }

        @Test
        @DisplayName("AC-175 STRIP_ACCENTS removes every diacritical mark")
        void stripAccents() {
            assertThat(value(FieldDefinition.builder("n").textTransform(TextTransform.STRIP_ACCENTS), "Ñandú"))
                    .isEqualTo("Nandu");
        }

        @Test
        void whitespaceOnlyIsEmptyAfterTrimming() {
            assertThat(value(FieldDefinition.builder("n").defaultValue("d"), " \t ")).isEqualTo("d");
        }
    }

    @Nested
    class Transformers {

        @Test
        @DisplayName("AC-48 a transformer returning null empties the value")
        void transformerReturnsNull() {
            transformers.put("x", (value, record, direction) -> null);

            assertThat(value(FieldDefinition.builder("n").transformer("x"), "v")).isNull();
        }

        @Test
        @DisplayName("AC-49 a transformer that throws rejects the line with its message on that field")
        void transformerThrows() {
            transformers.put("x", (value, record, direction) -> {
                throw new IllegalArgumentException("bad nif");
            });

            LineError error = error(FieldDefinition.builder("nif").transformer("x"), "v");

            assertThat(error.field()).isEqualTo("nif");
            assertThat(error.code()).isEqualTo(ErrorCode.TRANSFORMER_FAILED);
            assertThat(error.args()).containsExactly(entry("transformer", "x"), entry("exceptionMessage", "bad nif"));
            assertThat(error.message()).isEqualTo("transformer 'x' failed: bad nif");
        }

        @Test
        void transformerSeesTheDirectionAndTheFieldsComputedSoFar() {
            List<Object> seen = new ArrayList<>();
            transformers.put("x", (value, record, direction) -> {
                seen.add(direction);
                seen.add(record.get("born"));
                seen.add(record.has("later"));
                return value + "!";
            });

            Object value = accepted(engine(
                    FieldDefinition.builder("born").type(FieldType.DATE),
                    FieldDefinition.builder("n").transformer("x"),
                    FieldDefinition.builder("later")).process(LineInput.of(line("born", "2026-01-02", "n", "v", "later", "l"))))
                    .get("n");

            assertThat(value).isEqualTo("v!");
            assertThat(seen).containsExactly(Direction.IMPORT, LocalDate.of(2026, 1, 2), false);
        }

        @Test
        void transformerIsNotCalledForEmptyValues() {
            AtomicInteger calls = new AtomicInteger();
            transformers.put("x", (value, record, direction) -> {
                calls.incrementAndGet();
                return value;
            });

            assertThat(value(FieldDefinition.builder("n").transformer("x"), "  ")).isNull();
            assertThat(calls).hasValue(0);
        }

        @Test
        void exceptionWithoutMessageUsesItsSimpleName() {
            transformers.put("x", (value, record, direction) -> {
                throw new IllegalStateException();
            });

            assertThat(error(FieldDefinition.builder("n").transformer("x"), "v").args())
                    .containsEntry("exceptionMessage", "IllegalStateException");
        }

        @Test
        void unknownTransformerIsAProgrammingError() {
            assertThatThrownBy(() -> engine(FieldDefinition.builder("n").transformer("ghost")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("transformer 'ghost' is not registered");
        }
    }

    @Nested
    class Types {

        @Test
        @DisplayName("AC-50 DATE with dd/MM/yyyy is strict")
        void dateWithPattern() {
            FieldDefinition.Builder field = FieldDefinition.builder("d").type(FieldType.DATE)
                    .typeFormat(TypeFormat.date("dd/MM/yyyy"));

            assertThat(value(field, "31/12/2026")).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(error(field, "31/02/2026").code()).isEqualTo(ErrorCode.INVALID_TYPE);
            assertThat(error(field, "20261301").code()).isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @Test
        @DisplayName("AC-51 DECIMAL with ',' decimal and '.' grouping is exact")
        void decimalWithSeparators() {
            assertThat(value(FieldDefinition.builder("a").type(FieldType.DECIMAL)
                    .typeFormat(TypeFormat.decimal(',', '.')), "1.234,5678")).isEqualTo(new BigDecimal("1234.5678"));
        }

        @Test
        @DisplayName("AC-52 an invalid INTEGER")
        void invalidInteger() {
            assertThat(error(FieldDefinition.builder("a").type(FieldType.INTEGER), "12a").message())
                    .isEqualTo("value '12a' is not a valid INTEGER");
        }

        @Test
        @DisplayName("AC-53 BOOLEAN with S/N literals")
        void booleanLiterals() {
            FieldDefinition.Builder field = FieldDefinition.builder("b").type(FieldType.BOOLEAN)
                    .typeFormat(TypeFormat.booleanLiterals("S", "N"));

            assertThat(value(field, "S")).isEqualTo(true);
            assertThat(value(field, "N")).isEqualTo(false);
            assertThat(error(field, "X").code()).isEqualTo(ErrorCode.INVALID_TYPE);
            assertThat(error(field, "s").code()).isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @Test
        @DisplayName("AC-169 DATE defaults to ISO")
        void dateDefault() {
            FieldDefinition.Builder field = FieldDefinition.builder("d").type(FieldType.DATE);

            assertThat(value(field, "2026-12-31")).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(error(field, "31/12/2026").code()).isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @Test
        @DisplayName("AC-170 INTEGER is a Long; out of range is INVALID_TYPE")
        void integerRange() {
            FieldDefinition.Builder field = FieldDefinition.builder("i").type(FieldType.INTEGER);

            assertThat(value(field, "9223372036854775807")).isEqualTo(Long.MAX_VALUE);
            assertThat(value(field, "-42")).isEqualTo(-42L);
            assertThat(error(field, "9223372036854775808").code()).isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @Test
        @DisplayName("AC-171 DECIMAL defaults to '.' and no grouping")
        void decimalDefault() {
            FieldDefinition.Builder field = FieldDefinition.builder("a").type(FieldType.DECIMAL);

            assertThat(value(field, "1.5")).isEqualTo(new BigDecimal("1.5"));
            assertThat(error(field, "1,5").code()).isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @Test
        @DisplayName("AC-172 BOOLEAN defaults to true/false ignoring case")
        void booleanDefault() {
            FieldDefinition.Builder field = FieldDefinition.builder("b").type(FieldType.BOOLEAN);

            assertThat(value(field, "TRUE")).isEqualTo(true);
            assertThat(value(field, "False")).isEqualTo(false);
            assertThat(error(field, "si").code()).isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @ParameterizedTest(name = "{0} with {1}")
        @CsvSource(delimiter = '|', value = {
                "1.234.567,8 | 1234567.8",
                "-1.234,5    | -1234.5",
                "1234,5      | 1234.5",
                "12          | 12",
        })
        void groupedDecimals(String text, String expected) {
            assertThat(value(FieldDefinition.builder("a").type(FieldType.DECIMAL)
                    .typeFormat(TypeFormat.decimal(',', '.')), text)).isEqualTo(new BigDecimal(expected));
        }

        @ParameterizedTest
        @CsvSource({"'1,2,3'", "'.5'", "'1..234'", "'.123'", "'1.234.'", "'1e5'", "'1,2.3'", "'1234.5'", "'1.23'",
                "'12.3456'", "'1.2345,6'"})
        void malformedDecimals(String text) {
            assertThat(error(FieldDefinition.builder("a").type(FieldType.DECIMAL)
                    .typeFormat(TypeFormat.decimal(',', '.')), text).code()).isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @Test
        void integersWithGroupingAndDateTimes() {
            assertThat(value(FieldDefinition.builder("i").type(FieldType.INTEGER)
                    .typeFormat(TypeFormat.decimal(null, '.')), "1.234")).isEqualTo(1234L);
            assertThat(value(FieldDefinition.builder("t").type(FieldType.DATETIME), "2026-10-26T12:30:00"))
                    .isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 30));
            assertThat(value(FieldDefinition.builder("t").type(FieldType.DATETIME)
                    .typeFormat(TypeFormat.date("dd/MM/yyyy HH:mm")), "26/10/2026 12:30"))
                    .isEqualTo(LocalDateTime.of(2026, 10, 26, 12, 30));
            assertThat(value(FieldDefinition.builder("d").type(FieldType.DATE)
                    .typeFormat(TypeFormat.date("dd 'de' yyyy MM")), "31 de 2026 12")).isEqualTo(LocalDate.of(2026, 12, 31));
        }

        @Test
        void canonicalFormIsAcceptedOnlyWhenAsked() {
            ImportEngine engine = engine(
                    FieldDefinition.builder("d").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy")),
                    FieldDefinition.builder("a").type(FieldType.DECIMAL).typeFormat(TypeFormat.decimal(',', '.')));
            Map<String, String> canonical = line("d", "2026-12-31", "a", "1234.5");

            assertThat(rejected(engine.process(LineInput.of(canonical)))).hasSize(2);
            assertThat(accepted(engine.process(new LineInput(canonical, canonical, true))).asMap())
                    .containsExactly(entry("d", LocalDate.of(2026, 12, 31)), entry("a", new BigDecimal("1234.5")));
        }
    }

    @Nested
    class ValidationsAndEmptyValues {

        @Test
        @DisplayName("AC-54 maxLength")
        void maxLength() {
            FieldDefinition.Builder field = FieldDefinition.builder("a").validations(Validations.maxLength(5));

            assertThat(value(field, "abcd")).isEqualTo("abcd");
            assertThat(value(field, "abcde")).isEqualTo("abcde");
            assertThat(error(field, "abcdef").message()).isEqualTo("value is longer than 5");
        }

        @Test
        @DisplayName("AC-55 minLength")
        void minLength() {
            FieldDefinition.Builder field = FieldDefinition.builder("a").validations(Validations.minLength(2));

            assertThat(error(field, "a").code()).isEqualTo(ErrorCode.TOO_SHORT);
            assertThat(value(field, "ab")).isEqualTo("ab");
            assertThat(value(field, "abc")).isEqualTo("abc");
        }

        @Test
        @DisplayName("AC-56 regex must match the whole value")
        void regex() {
            FieldDefinition.Builder field = FieldDefinition.builder("nif").validations(Validations.regex("[0-9]{8}[A-Z]"));

            assertThat(error(field, "1234567X").code()).isEqualTo(ErrorCode.PATTERN_MISMATCH);
            assertThat(error(field, "12345678XX").code()).isEqualTo(ErrorCode.PATTERN_MISMATCH);
            assertThat(value(field, "12345678X")).isEqualTo("12345678X");
        }

        @Test
        @DisplayName("AC-57 allowedValues")
        void allowedValues() {
            LineError error = error(FieldDefinition.builder("a").validations(Validations.allowedValues(List.of("A", "B"))), "C");

            assertThat(error.code()).isEqualTo(ErrorCode.NOT_ALLOWED);
            assertThat(error.args()).containsExactly(entry("value", "C"), entry("allowedValues", "A,B"));
        }

        @Test
        @DisplayName("AC-58 validations do not apply to empty values; onEmpty decides")
        void validationsSkipEmpty() {
            Validations strict = new Validations(3, 5, "[a-z]+", List.of("abc"));

            assertThat(value(FieldDefinition.builder("a").validations(strict), "")).isNull();
            assertThat(error(FieldDefinition.builder("a").validations(strict).onEmpty(OnEmpty.ERROR), "").code())
                    .isEqualTo(ErrorCode.REQUIRED_EMPTY);
        }

        @Test
        @DisplayName("AC-59 empty value with EMPTY / KEEP / ERROR")
        void onEmpty() {
            FieldValues empty = accepted(engine(FieldDefinition.builder("a")).process(LineInput.of(line("a", ""))));
            FieldValues keep = accepted(engine(FieldDefinition.builder("a").onEmpty(OnEmpty.KEEP))
                    .process(LineInput.of(line("a", ""))));

            assertThat(empty.has("a")).isTrue();
            assertThat(empty.get("a")).isNull();
            assertThat(keep.has("a")).isFalse();
            assertThat(error(FieldDefinition.builder("a").onEmpty(OnEmpty.ERROR), "").message())
                    .isEqualTo("required field is empty");
        }

        @Test
        @DisplayName("AC-60 a literal default never calls the resolver")
        void literalDefault() {
            resolver = (template, record) -> {
                throw new AssertionError("resolver called");
            };

            assertThat(value(FieldDefinition.builder("country").defaultValue("ES"), "")).isEqualTo("ES");
        }

        @Test
        @DisplayName("AC-61 a template default is resolved with the record computed so far")
        void templateDefault() {
            List<Object> seen = new ArrayList<>();
            resolver = (template, record) -> {
                seen.add(template);
                seen.add(record.get("name"));
                seen.add(record.has("code"));
                return record.get("name") + "-x";
            };

            FieldValues values = accepted(engine(
                    FieldDefinition.builder("name"),
                    FieldDefinition.builder("code").defaultValue("${name}-x")).process(LineInput.of(line("name", "ab"))));

            assertThat(values.get("code")).isEqualTo("ab-x");
            assertThat(seen).containsExactly("${name}-x", "ab", false);
        }

        @Test
        @DisplayName("AC-62 a template default without resolver rejects the line")
        void templateWithoutResolver() {
            assertThat(error(FieldDefinition.builder("code").defaultValue("${name}-x"), "").message())
                    .isEqualTo("default value has placeholders and no resolver is registered");
        }

        @Test
        @DisplayName("AC-63 a copy of an empty KEEP field receives empty; the KEEP field is absent")
        void copyOfKeep() {
            FieldValues values = accepted(engine(
                    FieldDefinition.builder("B").onEmpty(OnEmpty.KEEP),
                    FieldDefinition.builder("C").copyFrom("B")).process(LineInput.of(line("B", ""))));

            assertThat(values.has("B")).isFalse();
            assertThat(values.has("C")).isTrue();
            assertThat(values.get("C")).isNull();
        }

        @Test
        @DisplayName("AC-64 two field errors make one rejection with both errors")
        void twoErrorsOneRejection() {
            LineResult result = engine(
                    FieldDefinition.builder("a").type(FieldType.INTEGER),
                    FieldDefinition.builder("b").onEmpty(OnEmpty.ERROR),
                    FieldDefinition.builder("c")).process(LineInput.of(line("a", "x", "c", "ok")));

            assertThat(rejected(result)).extracting(LineError::field, LineError::code).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("a", ErrorCode.INVALID_TYPE),
                    org.assertj.core.groups.Tuple.tuple("b", ErrorCode.REQUIRED_EMPTY));
        }

        @Test
        void defaultsAreConvertedToTheFieldType() {
            assertThat(value(FieldDefinition.builder("n").type(FieldType.INTEGER).defaultValue("1"), "")).isEqualTo(1L);
            assertThat(error(FieldDefinition.builder("n").type(FieldType.INTEGER).defaultValue("one"), "").code())
                    .isEqualTo(ErrorCode.INVALID_TYPE);
        }

        @Test
        void fieldsNotInInputIgnoreTheirInputAndUseDefaultOrCopy() {
            FieldValues values = accepted(engine(
                    FieldDefinition.builder("a"),
                    FieldDefinition.builder("b").inInput(false).defaultValue("1"),
                    FieldDefinition.builder("c").inInput(false).copyFrom("a"))
                    .process(LineInput.of(line("a", "x", "b", "ignored", "c", "ignored"))));

            assertThat(values.asMap()).containsExactly(entry("a", "x"), entry("b", "1"), entry("c", "x"));
        }

        @Test
        void validationsRunOnTheTransformedText() {
            FieldDefinition.Builder field = FieldDefinition.builder("a").textTransform(TextTransform.padLeft(5, '0'))
                    .validations(Validations.maxLength(4));

            assertThat(error(field, "1").args()).containsExactly(entry("value", "00001"), entry("maxLength", "4"));
        }

        @Test
        void allValidationErrorsOfAFieldAreReported() {
            List<LineError> errors = rejected(engine(FieldDefinition.builder("a")
                    .validations(new Validations(null, 2, "[0-9]+", null))).process(LineInput.of(line("a", "abc"))));

            assertThat(errors).extracting(LineError::code).containsExactly(ErrorCode.TOO_LONG, ErrorCode.PATTERN_MISMATCH);
        }
    }

    @Nested
    class ErrorArguments {

        @Test
        @DisplayName("AC-120 NO_TRANSLATION carries the value")
        void noTranslation() {
            LineError error = error(FieldDefinition.builder("sex").translation("M", "Male")
                    .onUnmatched(OnUnmatched.ERROR), "X");

            assertThat(error.code()).isEqualTo(ErrorCode.NO_TRANSLATION);
            assertThat(error.args()).containsExactly(entry("value", "X"));
        }

        @Test
        @DisplayName("AC-121 REQUIRED_EMPTY has no arguments")
        void requiredEmpty() {
            LineError error = error(FieldDefinition.builder("a").onEmpty(OnEmpty.ERROR), "");

            assertThat(error.code()).isEqualTo(ErrorCode.REQUIRED_EMPTY);
            assertThat(error.args()).isEmpty();
        }

        @Test
        @DisplayName("AC-122 INVALID_TYPE carries value, type and format")
        void invalidType() {
            LineError error = error(FieldDefinition.builder("d").type(FieldType.DATE)
                    .typeFormat(TypeFormat.date("dd/MM/yyyy")), "31/02/2026");

            assertThat(error.code()).isEqualTo(ErrorCode.INVALID_TYPE);
            assertThat(error.args()).containsExactly(
                    entry("value", "31/02/2026"), entry("type", "DATE"), entry("format", "dd/MM/yyyy"));
            assertThat(error(FieldDefinition.builder("i").type(FieldType.INTEGER), "x").args())
                    .containsOnlyKeys("value", "type");
        }

        @Test
        @DisplayName("AC-123 TOO_LONG carries maxLength")
        void tooLong() {
            LineError error = error(FieldDefinition.builder("a").validations(Validations.maxLength(5)), "abcdef");

            assertThat(error.code()).isEqualTo(ErrorCode.TOO_LONG);
            assertThat(error.args()).containsEntry("maxLength", "5");
        }

        @Test
        @DisplayName("AC-124 DEFAULT_UNRESOLVABLE carries the template")
        void defaultUnresolvable() {
            LineError error = error(FieldDefinition.builder("code").defaultValue("${name}-x"), "");

            assertThat(error.code()).isEqualTo(ErrorCode.DEFAULT_UNRESOLVABLE);
            assertThat(error.args()).containsExactly(entry("template", "${name}-x"));
        }

        @Test
        @DisplayName("AC-125 a resolver that throws gives DEFAULT_RESOLVER_FAILED")
        void resolverThrows() {
            resolver = (template, record) -> {
                throw new IllegalStateException("x");
            };

            LineError error = error(FieldDefinition.builder("code").defaultValue("${name}"), "");

            assertThat(error.code()).isEqualTo(ErrorCode.DEFAULT_RESOLVER_FAILED);
            assertThat(error.args()).containsExactly(entry("template", "${name}"), entry("exceptionMessage", "x"));
            assertThat(error.message()).isEqualTo("default value resolver failed: x");
        }

        @Test
        void everyEngineErrorUsesOnlyItsDeclaredArgumentNames() {
            transformers.put("boom", (value, record, direction) -> {
                throw new IllegalStateException("boom");
            });
            resolver = (template, record) -> {
                throw new IllegalStateException("r");
            };
            List<LineError> errors = rejected(engine(
                    FieldDefinition.builder("a").translation("M", "Male"),
                    FieldDefinition.builder("b").onEmpty(OnEmpty.ERROR),
                    FieldDefinition.builder("c").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy")),
                    FieldDefinition.builder("d").validations(new Validations(9, 1, "x", List.of("y"))),
                    FieldDefinition.builder("e").defaultValue("${t}"),
                    FieldDefinition.builder("f").transformer("boom")).process(LineInput.of(line(
                    "a", "X", "c", "nope", "d", "zz", "f", "v"))));

            assertThat(errors).extracting(LineError::code).containsExactly(ErrorCode.NO_TRANSLATION,
                    ErrorCode.REQUIRED_EMPTY, ErrorCode.INVALID_TYPE, ErrorCode.TOO_SHORT, ErrorCode.TOO_LONG,
                    ErrorCode.PATTERN_MISMATCH, ErrorCode.NOT_ALLOWED, ErrorCode.DEFAULT_RESOLVER_FAILED,
                    ErrorCode.TRANSFORMER_FAILED);
            for (LineError error : errors) {
                assertThat(error.code().argumentNames()).containsAll(error.args().keySet());
                assertThat(error.message()).isEqualTo(error.code().message(error.args())).doesNotContain("{");
            }
        }
    }

    @Nested
    class Invariants {

        @Test
        void inv4SameInputSameResult() {
            ImportEngine engine = engine(
                    FieldDefinition.builder("a").type(FieldType.DECIMAL),
                    FieldDefinition.builder("b").copyFrom("a").textTransform(TextTransform.padLeft(8, '0')),
                    FieldDefinition.builder("c").translation("M", "Male"));
            LineInput input = LineInput.of(line("a", "1.5", "c", "M"));

            assertThat(engine.process(input)).isEqualTo(engine.process(input));
            assertThat(engine.process(LineInput.of(line("a", "x", "c", "Q"))))
                    .isEqualTo(engine.process(LineInput.of(line("a", "x", "c", "Q"))));
        }

        @Test
        void inv5AbsentAndNullAreDifferent() {
            FieldValues values = accepted(engine(
                    FieldDefinition.builder("empty"),
                    FieldDefinition.builder("kept").onEmpty(OnEmpty.KEEP)).process(LineInput.of(line())));

            assertThat(values.asMap()).containsOnlyKeys("empty");
            assertThat(values.get("kept")).isNull();
            assertThat(values.has("kept")).isFalse();
        }

        @Test
        void inv7RejectedLinesCarryNoValues() {
            LineResult result = engine(FieldDefinition.builder("a").onEmpty(OnEmpty.ERROR), FieldDefinition.builder("b"))
                    .process(LineInput.of(line("b", "x")));

            assertThat(result).isInstanceOf(LineResult.Rejected.class);
            assertThat(result.rawValues()).containsExactly(entry("b", "x"));
        }

        @Test
        void valuesAreTypedImmutableAndInDeclarationOrder() {
            RecordView values = accepted(engine(
                    FieldDefinition.builder("z").copyFrom("a"),
                    FieldDefinition.builder("a").type(FieldType.INTEGER)).process(LineInput.of(line("a", "7"))));

            assertThat(((FieldValues) values).asMap()).containsExactly(entry("z", "7"), entry("a", 7L));
            assertThat(values.get("a", Long.class)).isEqualTo(7L);
            assertThatThrownBy(() -> values.get("a", String.class))
                    .isInstanceOf(ClassCastException.class)
                    .hasMessage("field 'a' is a Long, not a String");
            assertThatThrownBy(() -> ((FieldValues) values).asMap().put("x", 1))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(values.rawValues()).containsExactly(entry("a", "7"));
        }

        @Test
        void outRowsAreIgnoredOnImport() {
            FieldDefinition.Builder field = FieldDefinition.builder("s")
                    .translation(new Translation("Male", "M", TranslationDirection.OUT))
                    .translation(new Translation("F", "Female", TranslationDirection.IN))
                    .onUnmatched(OnUnmatched.PASS);

            assertThat(value(field, "Male")).isEqualTo("Male");
            assertThat(value(field, "F")).isEqualTo("Female");
        }
    }

    @Test
    void errorCodeMessagesAndArguments() {
        assertThat(ErrorCode.INVALID_TYPE.message(Map.of("value", "x", "type", "DATE"))).isEqualTo("value 'x' is not a valid DATE");
        assertThat(ErrorCode.PARENT_REJECTED.isFieldError()).isFalse();
        assertThat(ErrorCode.TOO_LONG.isFieldError()).isTrue();
        assertThat(LineError.of(null, ErrorCode.CONSUMER_REJECTED, "reason", "dup").message()).isEqualTo("dup");
        assertThatThrownBy(() -> LineError.of("a", ErrorCode.TOO_LONG, "length", "5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TOO_LONG has no argument 'length'");
    }
}
