package org.tie.internal.engine;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.tie.RecordView;
import org.tie.config.Direction;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;
import org.tie.spi.CompiledExpression;
import org.tie.spi.ExpressionContext;
import org.tie.spi.ExpressionEngine;

/** Field expressions (§4.3 «Expresiones», §4.4): what one run gives them and how they are compiled. */
public final class Expressions {

    private Expressions() {
    }

    /** What every expression of one run sees besides its record: today, the context variables, the direction. */
    public record Scope(LocalDate today, Map<String, String> variables, Direction direction) {

        public Scope {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }

        public static Scope of(Clock clock, Map<String, String> variables, Direction direction) {
            return new Scope(LocalDate.now(clock), variables, direction);
        }
    }

    /** The context of one evaluation; also the parent context handed to nested records. */
    record Context(Object value, RecordView record, ExpressionContext parent, LocalDate today,
                   Map<String, String> variables, Direction direction) implements ExpressionContext {
    }

    /** The context nested records see as {@code parent}: the final values of their parent record. */
    public static ExpressionContext parentOf(RecordView record, ExpressionContext parent, Scope scope) {
        return new Context(null, record, parent, scope.today(), scope.variables(), scope.direction());
    }

    /** {@link #parentOf(RecordView, ExpressionContext, Scope)} for the values of an exported record. */
    public static ExpressionContext parentOf(Map<String, Object> values, ExpressionContext parent, Scope scope) {
        return parentOf(new FieldValues(Collections.unmodifiableMap(values), Map.of()), parent, scope);
    }

    static Context context(Object value, RecordView record, ExpressionContext parent, Scope scope) {
        return new Context(value, record, parent, scope.today(), scope.variables(), scope.direction());
    }

    /**
     * The compiled expressions of a layout by field name, in declaration order.
     *
     * @throws IllegalStateException if a field has an expression and there is no engine (a validated
     *                               configuration never has)
     */
    static Map<String, CompiledExpression> compile(Layout layout, ExpressionEngine engine) {
        Map<String, CompiledExpression> compiled = new LinkedHashMap<>();
        for (FieldDefinition field : layout.fields()) {
            if (field.expression() == null) {
                continue;
            }
            if (engine == null) {
                throw new IllegalStateException("field '" + field.name() + "' has an expression and no ExpressionEngine"
                        + " is registered");
            }
            compiled.put(field.name(), engine.compile(field.expression()));
        }
        return compiled;
    }
}
