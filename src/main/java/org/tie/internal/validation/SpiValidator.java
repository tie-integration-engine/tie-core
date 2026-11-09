package org.tie.internal.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.tie.config.ChannelDefinition;
import org.tie.config.FieldDefinition;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.SftpChannel;
import org.tie.internal.Exceptions;
import org.tie.internal.secret.SecretReference;
import org.tie.spi.ExpressionEngine;

/**
 * Checks that the SPIs a definition needs are registered (§5.3). A {@code defaultValue} with
 * <code>${</code> without a resolver is <em>not</em> a problem: it fails per line (AC-04).
 */
public final class SpiValidator {

    private SpiValidator() {
    }

    public static List<String> validate(IntegrationDefinition definition, Set<String> transformers,
                                        boolean hasSecretProvider, ExpressionEngine expressionEngine) {
        List<String> problems = new ArrayList<>();
        List<Layout> layouts = definition.layouts();
        for (int i = 0; i < layouts.size(); i++) {
            String layoutLabel = Labels.layout(layouts.get(i), i);
            List<FieldDefinition> fields = layouts.get(i).fields();
            for (int j = 0; j < fields.size(); j++) {
                String transformer = fields.get(j).transformer();
                if (transformer != null && !transformer.isBlank() && !transformers.contains(transformer)) {
                    problems.add(Labels.field(layoutLabel, fields.get(j), j) + ": transformer '" + transformer
                            + "' is not registered");
                }
                expression(Labels.field(layoutLabel, fields.get(j), j), fields.get(j).expression(), expressionEngine,
                        problems);
            }
        }
        if (!hasSecretProvider) {
            List<ChannelDefinition> inputs = definition.inputChannels();
            for (int i = 0; i < inputs.size(); i++) {
                secrets("inputChannels[" + i + "]", inputs.get(i), problems);
            }
            secrets("outputChannel", definition.outputChannel(), problems);
            secrets("resultReport.channel", definition.resultReport().channel(), problems);
        }
        return problems;
    }

    /** An expression needs an engine, and must compile with it (§5.3). */
    private static void expression(String label, String source, ExpressionEngine engine, List<String> problems) {
        if (source == null) {
            return;
        }
        if (engine == null) {
            problems.add(label + ": expression requires an ExpressionEngine");
            return;
        }
        try {
            engine.compile(source);
        } catch (RuntimeException e) {
            problems.add(label + ": expression does not compile: " + Exceptions.message(e));
        }
    }

    /** Secret references of one channel without a SecretProvider, for {@code deliverFile}. */
    public static List<String> validateChannel(String path, ChannelDefinition channel, boolean hasSecretProvider) {
        List<String> problems = new ArrayList<>();
        if (!hasSecretProvider) {
            secrets(path, channel, problems);
        }
        return problems;
    }

    private static void secrets(String path, ChannelDefinition channel, List<String> problems) {
        if (!(channel instanceof SftpChannel sftp)) {
            return;
        }
        String label = Labels.channel(path, channel);
        secret(label, "password", sftp.password(), problems);
        secret(label, "privateKey", sftp.privateKey(), problems);
        secret(label, "privateKeyPassphrase", sftp.privateKeyPassphrase(), problems);
    }

    private static void secret(String label, String property, String value, List<String> problems) {
        SecretReference.name(value).ifPresent(name -> problems.add(
                label + ": " + property + " uses ${secret:" + name + "} but no SecretProvider is registered"));
    }
}
