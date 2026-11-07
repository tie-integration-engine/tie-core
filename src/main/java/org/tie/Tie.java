package org.tie;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.tie.config.ChannelDefinition;
import org.tie.config.IntegrationDefinition;
import org.tie.internal.DefaultTie;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.FieldTransformer;
import org.tie.spi.SecretProvider;

/**
 * Entry point of TIE (§5.2). Instances hold no state between executions and are safe for concurrent
 * use by different executions.
 *
 */
public interface Tie {

    /**
     * Validates a definition against this instance: the definition itself plus the SPIs it needs
     * (registered transformers, secret provider).
     *
     * @return every problem found; empty when the definition is valid
     */
    List<String> validate(IntegrationDefinition definition);

    /**
     * Runs an import (§3): selects the candidate files of every input channel, reads them in streaming,
     * and hands each valid typed record to the context's consumer.
     *
     * @return the result, with the new watermarks to store; failures of files are reported here
     * @throws ConfigurationException if the definition, the context or the SPIs are invalid; no file is
     *                                touched (A1)
     */
    ImportResult importFiles(IntegrationDefinition definition, ImportContext context);

    /**
     * Runs an export (§3): each record passes through the export field engine; valid ones are written in
     * streaming to the working directory and the file is delivered at the end, unless the threshold is
     * exceeded or the export is cancelled (A11, A15).
     *
     * @param records typed values per field name; a child layout's records are a {@code List<Map>} under
     *                its {@code nestedKey}
     * @throws ConfigurationException if the definition, the context or the SPIs are invalid
     */
    ExportResult exportRecords(IntegrationDefinition definition, Iterator<Map<String, Object>> records,
                               ExportContext context);

    /**
     * Delivers an existing local file as is through an output channel (§5.2), with the same rules as an
     * export: temporary name and rename, {@code onExistingFile}, host key and secrets. The local file is
     * never modified (INV-17); delivery failures are returned, not thrown.
     *
     * @throws ConfigurationException if the channel is invalid or a {@code ProvidedStreamChannel}, the name
     *                                is not safe, or the file does not exist or cannot be read
     */
    DeliveryResult deliverFile(ChannelDefinition outputChannel, Path file, String remoteName, DeliveryContext context);

    static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link Tie}. Every component is optional. */
    final class Builder {
        private final Map<String, FieldTransformer> transformers = new LinkedHashMap<>();
        private DefaultValueResolver defaultValueResolver;
        private SecretProvider secretProvider;
        private Clock clock = Clock.systemDefaultZone();
        private Path workingDirectory;

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

        /**
         * Working directory of the executions whose context does not set one; without it,
         * {@code java.io.tmpdir}.
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Tie build() {
            return new DefaultTie(Map.copyOf(transformers), defaultValueResolver, secretProvider, clock,
                    workingDirectory);
        }
    }
}
