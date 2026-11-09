package org.tie.fixtures;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.tie.spi.CompiledExpression;
import org.tie.spi.ExpressionEngine;
import org.tie.spi.ExpressionException;

/** A test {@link ExpressionEngine}: each source is a Java lambda defined in the test; counts evaluations. */
public final class MapExpressionEngine implements ExpressionEngine {

    private final Map<String, CompiledExpression> expressions = new HashMap<>();
    public final AtomicInteger evaluations = new AtomicInteger();

    public MapExpressionEngine define(String source, CompiledExpression expression) {
        expressions.put(source, expression);
        return this;
    }

    @Override
    public CompiledExpression compile(String source) {
        CompiledExpression expression = expressions.get(source);
        if (expression == null) {
            throw new ExpressionException("unknown expression '" + source + "'");
        }
        return context -> {
            evaluations.incrementAndGet();
            return expression.evaluate(context);
        };
    }
}
