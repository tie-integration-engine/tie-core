package org.tie.spi;

/**
 * Compiles the {@code expression} of a field (§4.3 «Expresiones», §5.3). Register one with
 * {@code Tie.builder().expressionEngine(...)}; the module {@code tie-jexl} provides a JEXL implementation.
 * Implementations must be thread-safe.
 */
public interface ExpressionEngine {

    /**
     * Compiles a source; called when a definition is validated and when a run starts.
     *
     * @throws ExpressionException if the source is not a valid expression for this engine, with a message
     *                             that names the problem
     */
    CompiledExpression compile(String source);
}
