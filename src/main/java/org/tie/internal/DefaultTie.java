package org.tie.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tie.ImportContext;
import org.tie.ImportResult;
import org.tie.Tie;
import org.tie.config.IntegrationDefinition;
import org.tie.internal.run.ImportRun;
import org.tie.internal.validation.DefinitionValidator;
import org.tie.internal.validation.SpiValidator;
import org.tie.spi.DefaultValueResolver;
import org.tie.spi.FieldTransformer;
import org.tie.spi.SecretProvider;

/** Default {@link Tie}; built by {@link Tie#builder()}. */
public final class DefaultTie implements Tie {

    private final Map<String, FieldTransformer> transformers;
    private final DefaultValueResolver defaultValueResolver;
    private final SecretProvider secretProvider;
    private final Clock clock;

    public DefaultTie(Map<String, FieldTransformer> transformers, DefaultValueResolver defaultValueResolver,
                      SecretProvider secretProvider, Clock clock) {
        this.transformers = Map.copyOf(transformers);
        this.defaultValueResolver = defaultValueResolver;
        this.secretProvider = secretProvider;
        this.clock = clock;
    }

    @Override
    public List<String> validate(IntegrationDefinition definition) {
        List<String> problems = new ArrayList<>(DefinitionValidator.validate(definition));
        if (definition != null) {
            problems.addAll(SpiValidator.validate(definition, transformers.keySet(), secretProvider != null));
        }
        return List.copyOf(problems);
    }

    @Override
    public ImportResult importFiles(IntegrationDefinition definition, ImportContext context) {
        return new ImportRun(definition, context, transformers, defaultValueResolver, secretProvider != null, clock).run();
    }
}
