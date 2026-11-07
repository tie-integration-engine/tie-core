package org.tie;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.tie.spi.RunListener;

/**
 * Everything an export needs besides its definition and records (§5.2).
 *
 * @param providedOutput   where the file goes when the output channel is a {@code ProvidedStreamChannel};
 *                         TIE writes the whole file and closes the stream
 * @param workingDirectory where the file is written before delivery; null uses the {@link Tie}'s default,
 *                         else {@code java.io.tmpdir}
 */
public record ExportContext(Map<String, String> variables, RunListener listener, CancellationToken cancellation,
                            Supplier<OutputStream> providedOutput, Path workingDirectory) {

    public ExportContext {
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables == null ? Map.of() : variables));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, String> variables = new LinkedHashMap<>();
        private RunListener listener;
        private CancellationToken cancellation;
        private Supplier<OutputStream> providedOutput;
        private Path workingDirectory;

        private Builder() {
        }

        public Builder variable(String name, String value) {
            variables.put(name, value);
            return this;
        }

        public Builder variables(Map<String, String> variables) {
            this.variables.putAll(variables);
            return this;
        }

        public Builder listener(RunListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder cancellation(CancellationToken cancellation) {
            this.cancellation = cancellation;
            return this;
        }

        public Builder providedOutput(Supplier<OutputStream> providedOutput) {
            this.providedOutput = providedOutput;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public ExportContext build() {
            return new ExportContext(variables, listener, cancellation, providedOutput, workingDirectory);
        }
    }
}
