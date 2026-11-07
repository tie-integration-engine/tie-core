package org.tie;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tie.spi.AlreadyProcessedPredicate;
import org.tie.spi.FileHook;
import org.tie.spi.RecordConsumer;
import org.tie.spi.RunListener;

/**
 * Everything an import needs besides its definition (§5.2): the state the caller keeps (watermarks,
 * already-processed predicate) and the components of this run. Build it with {@link #builder(RecordConsumer)}.
 *
 * @param workingDirectory where TIE keeps working copies; null uses the {@link Tie}'s default, else
 *                         {@code java.io.tmpdir}
 */
public record ImportContext(Map<String, String> variables, Map<String, LocalDateTime> watermarks,
                            AlreadyProcessedPredicate alreadyProcessed, RecordConsumer consumer, RunListener listener,
                            CancellationToken cancellation, List<ProvidedInput> providedInputs, Path workingDirectory,
                            FileHook fileHook) {

    public ImportContext {
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables == null ? Map.of() : variables));
        watermarks = Collections.unmodifiableMap(new LinkedHashMap<>(watermarks == null ? Map.of() : watermarks));
        providedInputs = providedInputs == null ? List.of() : List.copyOf(providedInputs);
    }

    public static Builder builder(RecordConsumer consumer) {
        return new Builder().consumer(consumer);
    }

    public static final class Builder {
        private final Map<String, String> variables = new LinkedHashMap<>();
        private final Map<String, LocalDateTime> watermarks = new LinkedHashMap<>();
        private final List<ProvidedInput> providedInputs = new ArrayList<>();
        private AlreadyProcessedPredicate alreadyProcessed;
        private RecordConsumer consumer;
        private RunListener listener;
        private CancellationToken cancellation;
        private Path workingDirectory;
        private FileHook fileHook;

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

        public Builder watermark(String layout, LocalDateTime watermark) {
            watermarks.put(layout, watermark);
            return this;
        }

        public Builder watermarks(Map<String, LocalDateTime> watermarks) {
            this.watermarks.putAll(watermarks);
            return this;
        }

        public Builder alreadyProcessed(AlreadyProcessedPredicate alreadyProcessed) {
            this.alreadyProcessed = alreadyProcessed;
            return this;
        }

        public Builder consumer(RecordConsumer consumer) {
            this.consumer = consumer;
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

        public Builder providedInput(ProvidedInput input) {
            providedInputs.add(input);
            return this;
        }

        public Builder providedInput(String layout, String fileName, java.io.InputStream stream) {
            return providedInput(new ProvidedInput(layout, fileName, stream));
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder fileHook(FileHook fileHook) {
            this.fileHook = fileHook;
            return this;
        }

        public ImportContext build() {
            return new ImportContext(variables, watermarks, alreadyProcessed, consumer, listener, cancellation,
                    providedInputs, workingDirectory, fileHook);
        }
    }
}
