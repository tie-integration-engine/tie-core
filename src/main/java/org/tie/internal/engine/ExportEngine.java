package org.tie.internal.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.tie.ErrorCode;
import org.tie.LineError;
import org.tie.config.Direction;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;
import org.tie.config.OnUnmatched;
import org.tie.config.OutputAs;
import org.tie.config.Translation;
import org.tie.config.Validations;
import org.tie.internal.Exceptions;
import org.tie.spi.CompiledExpression;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.ExpressionContext;
import org.tie.spi.ExpressionEngine;
import org.tie.spi.FieldTransformer;

/**
 * The export field engine (§4.4): from the typed values of one supplied record to the values the
 * writer gets, through 1 read, 2 copy, 3 validations (on the canonical text), 4 conversion to text with
 * {@code typeFormat}, 5 text transforms, 6 translation (OUT rows, BOTH rows inverted), 7 custom
 * transformer and 8 empty resolution ({@code KEEP} behaves as {@code EMPTY}). With an effective
 * {@code outputAs NATIVE} the typed value goes to the writer and steps 4-7 do not apply.
 *
 * <p>A copy takes the source's supplied value. Immutable and thread-safe; one per layout.
 */
public final class ExportEngine {

    /** Outcome of one record. */
    public sealed interface Result {
    }

    /**
     * Values per field name in declaration order: a String (TEXT), a typed value (NATIVE) or null.
     * {@code evaluated} is the record after the expressions, which nested records see as {@code parent}.
     */
    public record Written(Map<String, Object> values, Map<String, Object> evaluated) implements Result {
    }

    public record Rejected(List<LineError> errors) implements Result {
        public Rejected {
            errors = List.copyOf(errors);
        }
    }

    private final List<Plan> order;
    private final List<String> declared;
    private final Map<String, Integer> declaredIndex = new HashMap<>();
    private final DefaultValueResolver resolver;
    /** Compiled expressions by field name, in declaration order. */
    private final Map<String, CompiledExpression> expressions;
    private final Map<String, FieldDefinition> fieldsByName = new HashMap<>();

    public ExportEngine(Layout layout, Map<String, FieldTransformer> transformers, DefaultValueResolver resolver) {
        this(layout, transformers, resolver, null);
    }

    public ExportEngine(Layout layout, Map<String, FieldTransformer> transformers, DefaultValueResolver resolver,
                        ExpressionEngine expressionEngine) {
        this.resolver = resolver;
        this.expressions = Expressions.compile(layout, expressionEngine);
        layout.fields().forEach(field -> fieldsByName.put(field.name(), field));
        List<String> names = new ArrayList<>();
        Map<String, Plan> byKey = new LinkedHashMap<>();
        for (FieldDefinition field : layout.fields()) {
            FieldTransformer transformer = field.transformer() == null ? null : transformers.get(field.transformer());
            if (field.transformer() != null && transformer == null) {
                throw new IllegalStateException("transformer '" + field.transformer() + "' is not registered");
            }
            byKey.put(FieldOrder.key(field.name()), new Plan(field, transformer));
            declaredIndex.put(field.name(), names.size());
            names.add(field.name());
        }
        this.declared = List.copyOf(names);
        this.order = FieldOrder.of(layout.fields()).stream().map(f -> byKey.get(FieldOrder.key(f.name()))).toList();
    }

    /** Processes a root record with today's date and no context variables. */
    public Result process(Map<String, Object> record) {
        return process(record, new Expressions.Scope(LocalDate.now(), Map.of(), Direction.EXPORT), null);
    }

    /**
     * Processes one record: first its expressions over the supplied values (§4.4), then the steps of §4.4.
     *
     * @param parent the parent record of a nested record, or null
     */
    public Result process(Map<String, Object> supplied, Expressions.Scope scope, ExpressionContext parent) {
        Map<String, Object> record = supplied;
        if (!expressions.isEmpty()) {
            Map<String, Object> working = new LinkedHashMap<>(supplied);
            List<LineError> errors = evaluate(working, scope, parent);
            if (!errors.isEmpty()) {
                errors.sort(Comparator.comparingInt(error -> declaredIndex.getOrDefault(error.field(), Integer.MAX_VALUE)));
                return new Rejected(errors);
            }
            record = working;
        }
        Map<String, Object> values = new HashMap<>();
        Map<String, Object> soFar = new LinkedHashMap<>();
        FieldValues view = new FieldValues(Collections.unmodifiableMap(soFar), Map.of());
        List<LineError> errors = new ArrayList<>();
        for (Plan plan : order) {
            FieldDefinition field = plan.field();
            Object input = record.get(field.copyFrom() != null && field.expression() == null
                    ? sourceName(field.copyFrom()) : field.name());
            Object value;
            try {
                value = compute(plan, input, view);
            } catch (FieldError error) {
                errors.addAll(error.errors);
                continue;
            }
            values.put(field.name(), value);
            soFar.put(field.name(), value);
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparingInt(error -> declaredIndex.getOrDefault(error.field(), Integer.MAX_VALUE)));
            return new Rejected(errors);
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        declared.forEach(name -> ordered.put(name, values.get(name)));
        return new Written(Collections.unmodifiableMap(ordered), Collections.unmodifiableMap(record));
    }

    /**
     * The expressions over the supplied values, in declaration order: each sees its field's supplied value (or
     * its copy source's) and the record with the results so far; its result replaces the field's value. A
     * String is accepted as is (step 1 reads text); another value must be representable as the field's type.
     */
    private List<LineError> evaluate(Map<String, Object> working, Expressions.Scope scope, ExpressionContext parent) {
        List<LineError> errors = new ArrayList<>();
        FieldValues view = new FieldValues(Collections.unmodifiableMap(working), Map.of());
        for (Map.Entry<String, CompiledExpression> entry : expressions.entrySet()) {
            FieldDefinition field = fieldsByName.get(entry.getKey());
            Object current = working.get(field.copyFrom() != null ? sourceName(field.copyFrom()) : field.name());
            Object result;
            try {
                result = entry.getValue().evaluate(Expressions.context(current, view, parent, scope));
            } catch (RuntimeException e) {
                errors.add(LineError.of(field.name(), ErrorCode.EXPRESSION_FAILED, "exceptionMessage",
                        Exceptions.message(e)));
                continue;
            }
            if (result instanceof String text && text.isEmpty()) {
                result = null;
            }
            if (result != null && !(result instanceof String) && Values.coerce(result, field.type()).isEmpty()) {
                errors.add(LineError.of(field.name(), ErrorCode.EXPRESSION_FAILED, "exceptionMessage",
                        "unsupported result type " + result.getClass().getSimpleName() + " for a " + field.type()
                                + " field"));
                continue;
            }
            working.put(field.name(), result);
        }
        return errors;
    }

    /** The key of the copy source in the record: the declared name of the source field. */
    private String sourceName(String copyFrom) {
        for (String name : declared) {
            if (name.equalsIgnoreCase(copyFrom)) {
                return name;
            }
        }
        return copyFrom;
    }

    private Object compute(Plan plan, Object input, FieldValues soFar) {
        FieldDefinition field = plan.field();
        boolean nativeOutput = field.effectiveOutputAs() == OutputAs.NATIVE;
        Object value = input instanceof CharSequence text && text.toString().isBlank() ? null : input;
        if (value != null) {
            Optional<Object> typed = Values.coerce(value, field.type());
            if (typed.isEmpty()) {
                throw new FieldError(invalidType(field, String.valueOf(value)));
            }
            List<LineError> validation = validate(plan, Values.canonical(typed.get()));
            if (!validation.isEmpty()) {
                throw new FieldError(validation);
            }
            if (nativeOutput) {
                return typed.get();
            }
            String text = emptyToNull(Values.format(typed.get(), field.typeFormat()));
            if (text != null) {
                text = emptyToNull(TextTransforms.apply(field.textTransforms(), text));
            }
            if (text != null && !field.translations().isEmpty()) {
                if (plan.translations().containsKey(text)) {
                    text = emptyToNull(plan.translations().get(text));
                } else if (field.onUnmatched() == OnUnmatched.EMPTY) {
                    text = null;
                } else if (field.onUnmatched() == OnUnmatched.ERROR) {
                    throw new FieldError(LineError.of(field.name(), ErrorCode.NO_TRANSLATION, "value", text));
                }
            }
            if (text != null && plan.transformer() != null) {
                try {
                    text = emptyToNull(plan.transformer().apply(text, soFar, Direction.EXPORT));
                } catch (RuntimeException e) {
                    throw new FieldError(LineError.of(field.name(), ErrorCode.TRANSFORMER_FAILED,
                            "transformer", field.transformer(), "exceptionMessage", Exceptions.message(e)));
                }
            }
            if (text != null) {
                return text;
            }
        }
        return empty(plan, soFar, nativeOutput);
    }

    private Object empty(Plan plan, FieldValues soFar, boolean nativeOutput) {
        FieldDefinition field = plan.field();
        return switch (field.onEmpty()) {
            case EMPTY, KEEP -> null;
            case ERROR -> throw new FieldError(LineError.of(field.name(), ErrorCode.REQUIRED_EMPTY));
            case DEFAULT -> {
                String template = field.defaultValue();
                String resolved = template;
                if (template.contains("${")) {
                    if (resolver == null) {
                        throw new FieldError(LineError.of(field.name(), ErrorCode.DEFAULT_UNRESOLVABLE, "template", template));
                    }
                    try {
                        resolved = resolver.resolve(template, soFar);
                    } catch (RuntimeException e) {
                        throw new FieldError(LineError.of(field.name(), ErrorCode.DEFAULT_RESOLVER_FAILED,
                                "template", template, "exceptionMessage", Exceptions.message(e)));
                    }
                }
                resolved = emptyToNull(resolved);
                if (resolved == null || !nativeOutput) {
                    yield resolved;
                }
                yield Values.coerce(resolved, field.type()).orElseThrow(() -> new FieldError(invalidType(field, template)));
            }
        };
    }

    private static List<LineError> validate(Plan plan, String text) {
        Validations validations = plan.field().validations();
        String name = plan.field().name();
        List<LineError> errors = new ArrayList<>();
        int length = text.codePointCount(0, text.length());
        if (validations.minLength() != null && length < validations.minLength()) {
            errors.add(LineError.of(name, ErrorCode.TOO_SHORT, "value", text, "minLength", validations.minLength().toString()));
        }
        if (validations.maxLength() != null && length > validations.maxLength()) {
            errors.add(LineError.of(name, ErrorCode.TOO_LONG, "value", text, "maxLength", validations.maxLength().toString()));
        }
        if (plan.regex() != null && !plan.regex().matcher(text).matches()) {
            errors.add(LineError.of(name, ErrorCode.PATTERN_MISMATCH, "value", text, "regex", validations.regex()));
        }
        if (validations.allowedValues() != null && !validations.allowedValues().contains(text)) {
            errors.add(LineError.of(name, ErrorCode.NOT_ALLOWED, "value", text,
                    "allowedValues", String.join(",", validations.allowedValues())));
        }
        return errors;
    }

    private static LineError invalidType(FieldDefinition field, String value) {
        return LineError.of(field.name(), ErrorCode.INVALID_TYPE, "value", value, "type", field.type().name());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record Plan(FieldDefinition field, FieldTransformer transformer, Map<String, String> translations,
                        Pattern regex) {

        Plan(FieldDefinition field, FieldTransformer transformer) {
            this(field, transformer, exportTranslations(field),
                    field.validations().regex() == null ? null : Pattern.compile(field.validations().regex()));
        }

        /** OUT rows as they are and BOTH rows inverted (AC-93); IN rows do not apply (AC-94). */
        private static Map<String, String> exportTranslations(FieldDefinition field) {
            Map<String, String> table = new HashMap<>();
            for (Translation translation : field.translations()) {
                switch (translation.direction()) {
                    case OUT -> table.putIfAbsent(translation.from(), translation.to());
                    case BOTH -> {
                        if (translation.to() != null) {
                            table.putIfAbsent(translation.to(), translation.from());
                        }
                    }
                    case IN -> {
                    }
                }
            }
            return table;
        }
    }

    /** Stops one field; the others are still computed. */
    private static final class FieldError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<LineError> errors;

        FieldError(LineError error) {
            this(List.of(error));
        }

        FieldError(List<LineError> errors) {
            super(null, null, false, false);
            this.errors = errors;
        }
    }
}
