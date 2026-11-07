package org.tie;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.tie.config.IntegrationDefinition;
import org.tie.internal.DefaultTie;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.FieldTransformer;
import org.tie.spi.SecretProvider;

/**
 * Entry point of TIE (§5.2). Instances hold no state between executions and are safe for concurrent
 * use by different executions.
 *
 * <p>Imports, exports and file delivery are added in later phases of the implementation plan.
 */
public interface Tie {

    /**
     * Validates a definition against this instance: the definition itself plus the SPIs it needs
     * (registered transformers, secret provider).
     *
     * @return every problem found; empty when the definition is valid
     */
    List<String> validate(IntegrationDefinition definition);

    static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link Tie}. Every component is optional. */
    final class Builder {
        private final Map<String, FieldTransformer> transformers = new LinkedHashMap<>();
        private DefaultValueResolver defaultValueResolver;
        private SecretProvider secretProvider;
        private Clock clock = Clock.systemDefaultZone();

        private Builder() {
        }

        /**
         * Registers a transformer that fields reference by {@code name}.
         *
         * @throws IllegalArgumentException if the name is blank or already registered
         */
        public Builder transformer(String name, FieldTransformer transformer) {
            Objects.requireNonNull(transformer, "transformer");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("transformer name must not be blank");
            }
            if (transformers.putIfAbsent(name, transformer) != null) {
                throw new IllegalArgumentException("transformer '" + name + "' is already registered");
            }
            return this;
        }

        public Builder defaultValueResolver(DefaultValueResolver defaultValueResolver) {
            this.defaultValueResolver = defaultValueResolver;
            return this;
        }

        public Builder secretProvider(SecretProvider secretProvider) {
            this.secretProvider = secretProvider;
            return this;
        }

        /** Clock for name timestamps and collision suffixes; defaults to the system clock. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Tie build() {
            return new DefaultTie(Map.copyOf(transformers), defaultValueResolver, secretProvider, clock);
        }
    }
}
