package org.tie.internal.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.tie.ConfigurationException;
import org.tie.DeliveryContext;
import org.tie.DeliveryResult;
import org.tie.ResultStatus;
import org.tie.Warning;
import org.tie.config.ChannelDefinition;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnExistingFile;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.SftpChannel;
import org.tie.internal.channel.ChannelException;
import org.tie.internal.channel.FileNames;
import org.tie.internal.delivery.Delivered;
import org.tie.internal.delivery.OutputChannel;
import org.tie.internal.validation.DefinitionValidator;
import org.tie.internal.validation.SpiValidator;

/** {@code Tie.deliverFile} (§5.2, A13): an existing local file delivered as is. */
public final class FileDelivery {

    private FileDelivery() {
    }

    public static DeliveryResult deliver(ChannelDefinition channel, Path file, String remoteName, DeliveryContext context,
                                         boolean hasSecretProvider, Clock clock) {
        List<String> problems = new ArrayList<>();
        if (channel == null) {
            problems.add("outputChannel is required");
        } else if (channel instanceof ProvidedStreamChannel) {
            problems.add("deliverFile requires an SFTP or local directory channel, not a ProvidedStreamChannel");
        } else {
            problems.addAll(DefinitionValidator.validateChannel("outputChannel", channel));
            problems.addAll(SpiValidator.validateChannel("outputChannel", channel, hasSecretProvider));
            if (channel instanceof SftpChannel) {
                problems.add("SFTP channels are not available in this build yet");
            }
        }
        if (remoteName == null || !FileNames.isSafe(remoteName)) {
            problems.add("remoteName '" + remoteName + "' is not a safe file name");
        }
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            problems.add("file " + file + " does not exist or cannot be read");
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        List<Warning> warnings = new ArrayList<>();
        try (OutputChannel output = OutputChannels.open(channel, clock, null)) {
            Delivered delivered = output.deliver(file, remoteName, onExistingFile(channel), warning -> {
                warnings.add(warning);
                if (context != null && context.listener() != null) {
                    context.listener().onWarning(warning);
                }
            });
            return new DeliveryResult(ResultStatus.SUCCESS, delivered.fileName(), delivered.destination(), null, warnings);
        } catch (ChannelException e) {
            return new DeliveryResult(ResultStatus.FAILED, remoteName, null, e.toFileError(), warnings);
        }
    }

    private static OnExistingFile onExistingFile(ChannelDefinition channel) {
        return switch (channel) {
            case LocalDirectoryChannel local -> local.onExistingFile();
            case SftpChannel sftp -> sftp.onExistingFile();
            case ProvidedStreamChannel ignored -> OnExistingFile.OVERWRITE;
        };
    }
}
