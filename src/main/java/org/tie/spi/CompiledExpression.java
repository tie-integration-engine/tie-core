package org.tie.spi;

/** A compiled field expression; must be thread-safe and must not keep the context it receives. */
@FunctionalInterface
public interface CompiledExpression {

    /**
     * Evaluates the expression for one record. The result replaces the field's value (§4.3 «Expresiones»):
     * null or an empty String leave it empty; a value of the field's Java type is used as is; a String is
     * converted like a value read from the file. Any exception rejects the line with {@code EXPRESSION_FAILED}.
     */
    Object evaluate(ExpressionContext context);
}
