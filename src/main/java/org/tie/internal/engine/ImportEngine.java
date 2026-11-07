package org.tie.internal.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.tie.ErrorCode;
import org.tie.LineError;
import org.tie.config.Direction;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;
import org.tie.config.OnUnmatched;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.Validations;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.FieldTransformer;

/**
 * The import field engine (§4.3): a pure function from the values read for one line to typed
 * values or every field error. Fields are computed in topological order of their copies (INV-6),
 * each through the steps 1 read, 2 copy, 3 trim, 4 text transforms, 5 translate (IN and BOTH rows),
 * 6 custom transformer, 7 convert to the type, 8 validations and 9 resolve empty; at the end of the
 * line, 10 drops the KEEP fields.
 *
 * <p>A step that fails stops that field; the other fields are still computed so that the line reports
 * all its errors at once (AC-64). The custom transformer is only called with non-empty values.
 *
 * <p>Instances are immutable and thread-safe; build one per layout.
 */
public final class ImportEngine {

    private final List<FieldPlan> order;
    private final List<String> declaredNames;
    private final Map<String, Integer> declaredIndex;
    private final DefaultValueResolver defaultValueResolver;

    /**
     * @throws IllegalStateException if a field names a transformer that is not in {@code transformers}
     *                               (a validated configuration never does)
     */
    public ImportEngine(Layout layout, Map<String, FieldTransformer> transformers,
                        DefaultValueResolver defaultValueResolver) {
        this.defaultValueResolver = defaultValueResolver;
        Map<String, FieldPlan> byKey = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, Integer> indexes = new HashMap<>();
        for (FieldDefinition field : layout.fields()) {
            FieldTransformer transformer = null;
            if (field.transformer() != null) {
                transformer = transformers.get(field.transformer());
                if (transformer == null) {
                    throw new IllegalStateException("transformer '" + field.transformer() + "' is not registered");
                }
            }
            byKey.put(key(field.name()), new FieldPlan(field, transformer));
            indexes.put(field.name(), names.size());
            names.add(field.name());
        }
        this.order = topologicalOrder(byKey);
        this.declaredNames = List.copyOf(names);
        this.declaredIndex = Map.copyOf(indexes);
    }

    public LineResult process(LineInput input) {
        Map<String, Object> values = new LinkedHashMap<>();
        FieldValues soFar = new FieldValues(Collections.unmodifiableMap(values), input.rawValues());
        Map<String, String> texts = new HashMap<>();
        Set<String> kept = new HashSet<>();
        List<LineError> errors = new ArrayList<>();

        for (FieldPlan plan : order) {
            FieldDefinition field = plan.field();
            String text = field.inInput() ? input.fieldInputs().get(field.name()) : null;
            if (field.copyFrom() != null) {
                text = texts.get(key(field.copyFrom()));
            }
            Outcome outcome = compute(plan, text, soFar, input.canonicalFallback());
            texts.put(key(field.name()), outcome.text());
            if (!outcome.errors().isEmpty()) {
                errors.addAll(outcome.errors());
            } else if (outcome.kept()) {
                kept.add(field.name());
            } else {
                values.put(field.name(), outcome.value());
            }
        }

        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparingInt(error -> declaredIndex.getOrDefault(error.field(), Integer.MAX_VALUE)));
            return new LineResult.Rejected(errors, input.rawValues());
        }
        kept.forEach(values::remove);
        return new LineResult.Accepted(FieldValues.frozen(values, declaredNames, input.rawValues()));
    }

    // ---------------------------------------------------------------- one field

    private Outcome compute(FieldPlan plan, String input, FieldValues soFar, boolean canonicalFallback) {
        FieldDefinition field = plan.field();
        String value = emptyToNull(input == null ? null : input.strip());
        if (value != null) {
            value = emptyToNull(TextTransforms.apply(field.textTransforms(), value));
        }
        if (value != null && plan.hasTranslations()) {
            if (plan.translations().containsKey(value)) {
                value = emptyToNull(plan.translations().get(value));
            } else if (field.onUnmatched() == OnUnmatched.EMPTY) {
                value = null;
            } else if (field.onUnmatched() == OnUnmatched.ERROR) {
                return Outcome.error(LineError.of(field.name(), ErrorCode.NO_TRANSLATION, "value", value));
            }
        }
        if (value != null && plan.transformer() != null) {
            try {
                value = emptyToNull(plan.transformer().apply(value, soFar, Direction.IMPORT));
            } catch (RuntimeException e) {
                return Outcome.error(LineError.of(field.name(), ErrorCode.TRANSFORMER_FAILED,
                        "transformer", field.transformer(), "exceptionMessage", exceptionMessage(e)));
            }
        }
        if (value == null) {
            return resolveEmpty(plan, soFar, canonicalFallback);
        }
        Optional<Object> typed = plan.converter().convert(value, canonicalFallback);
        if (typed.isEmpty()) {
            return Outcome.error(invalidType(plan, value));
        }
        List<LineError> validationErrors = validate(plan, value);
        if (!validationErrors.isEmpty()) {
            return new Outcome(null, value, false, validationErrors);
        }
        return Outcome.value(typed.get(), value);
    }

    private Outcome resolveEmpty(FieldPlan plan, FieldValues soFar, boolean canonicalFallback) {
        FieldDefinition field = plan.field();
        return switch (field.onEmpty()) {
            case EMPTY -> Outcome.value(null, null);
            case KEEP -> Outcome.KEPT;
            case ERROR -> Outcome.error(LineError.of(field.name(), ErrorCode.REQUIRED_EMPTY));
            case DEFAULT -> {
                String template = field.defaultValue();
                String resolved = template;
                if (template.contains("${")) {
                    if (defaultValueResolver == null) {
                        yield Outcome.error(LineError.of(field.name(), ErrorCode.DEFAULT_UNRESOLVABLE,
                                "template", template));
                    }
                    try {
                        resolved = defaultValueResolver.resolve(template, soFar);
                    } catch (RuntimeException e) {
                        yield Outcome.error(LineError.of(field.name(), ErrorCode.DEFAULT_RESOLVER_FAILED,
                                "template", template, "exceptionMessage", exceptionMessage(e)));
                    }
                }
                resolved = emptyToNull(resolved);
                if (resolved == null) {
                    yield Outcome.value(null, null);
                }
                Optional<Object> typed = plan.converter().convert(resolved, canonicalFallback);
                yield typed.isPresent() ? Outcome.value(typed.get(), resolved) : Outcome.error(invalidType(plan, resolved));
            }
        };
    }

    private static List<LineError> validate(FieldPlan plan, String value) {
        Validations validations = plan.field().validations();
        String name = plan.field().name();
        List<LineError> errors = new ArrayList<>();
        int length = value.codePointCount(0, value.length());
        if (validations.minLength() != null && length < validations.minLength()) {
            errors.add(LineError.of(name, ErrorCode.TOO_SHORT, "value", value,
                    "minLength", validations.minLength().toString()));
        }
        if (validations.maxLength() != null && length > validations.maxLength()) {
            errors.add(LineError.of(name, ErrorCode.TOO_LONG, "value", value,
                    "maxLength", validations.maxLength().toString()));
        }
        if (plan.regex() != null && !plan.regex().matcher(value).matches()) {
            errors.add(LineError.of(name, ErrorCode.PATTERN_MISMATCH, "value", value, "regex", validations.regex()));
        }
        if (validations.allowedValues() != null && !validations.allowedValues().contains(value)) {
            errors.add(LineError.of(name, ErrorCode.NOT_ALLOWED, "value", value,
                    "allowedValues", String.join(",", validations.allowedValues())));
        }
        return errors;
    }

    private static LineError invalidType(FieldPlan plan, String value) {
        return LineError.of(plan.field().name(), ErrorCode.INVALID_TYPE, "value", value,
                "type", plan.converter().type().name(), "format", plan.converter().formatDescription());
    }

    // ---------------------------------------------------------------- helpers

    /** Declaration order, except that a copy source is always computed before its copies (INV-6). */
    private static List<FieldPlan> topologicalOrder(Map<String, FieldPlan> byKey) {
        List<FieldPlan> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String key : byKey.keySet()) {
            visit(key, byKey, visited, new HashSet<>(), order);
        }
        return List.copyOf(order);
    }

    private static void visit(String key, Map<String, FieldPlan> byKey, Set<String> visited, Set<String> inProgress,
                              List<FieldPlan> order) {
        if (visited.contains(key)) {
            return;
        }
        if (!inProgress.add(key)) {
            throw new IllegalStateException("copy cycle through field '" + key + "'");
        }
        FieldPlan plan = byKey.get(key);
        String source = plan.field().copyFrom();
        if (source != null) {
            if (!byKey.containsKey(key(source))) {
                throw new IllegalStateException("copyFrom '" + source + "' does not exist");
            }
            visit(key(source), byKey, visited, inProgress, order);
        }
        visited.add(key);
        order.add(plan);
    }

    static String exceptionMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** A field with everything that can be prepared once per layout. */
    private record FieldPlan(FieldDefinition field, FieldTransformer transformer, TypeConverter converter,
                             Map<String, String> translations, Pattern regex) {

        FieldPlan(FieldDefinition field, FieldTransformer transformer) {
            this(field, transformer, new TypeConverter(field.type(), field.typeFormat()), importTranslations(field),
                    field.validations().regex() == null ? null : Pattern.compile(field.validations().regex()));
        }

        boolean hasTranslations() {
            return !field.translations().isEmpty();
        }

        private static Map<String, String> importTranslations(FieldDefinition field) {
            Map<String, String> table = new HashMap<>();
            for (Translation translation : field.translations()) {
                if (translation.direction() != TranslationDirection.OUT) {
                    table.putIfAbsent(translation.from(), translation.to());
                }
            }
            return table;
        }
    }

    /** Result of one field: a value (possibly null = empty), KEEP, or errors. */
    private record Outcome(Object value, String text, boolean kept, List<LineError> errors) {

        static final Outcome KEPT = new Outcome(null, null, true, List.of());

        static Outcome value(Object value, String text) {
            return new Outcome(value, text, false, List.of());
        }

        static Outcome error(LineError error) {
            return new Outcome(null, null, false, List.of(Objects.requireNonNull(error)));
        }
    }
}
