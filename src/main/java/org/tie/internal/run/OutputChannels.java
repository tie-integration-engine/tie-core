package org.tie.internal.run;

import java.io.OutputStream;
import java.time.Clock;
import java.util.function.Supplier;
import org.tie.config.ChannelDefinition;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.SftpChannel;
import org.tie.internal.delivery.LocalDirectoryOutput;
import org.tie.internal.delivery.OutputChannel;
import org.tie.internal.delivery.ProvidedStreamOutput;
import org.tie.internal.channel.sftp.SftpOutput;
import org.tie.spi.SecretProvider;

/** Opens the {@link OutputChannel} of a channel definition. */
final class OutputChannels {

    private OutputChannels() {
    }

    static OutputChannel open(ChannelDefinition channel, Clock clock, Supplier<OutputStream> providedOutput,
                              SecretProvider secrets) {
        return switch (channel) {
            case LocalDirectoryChannel local -> new LocalDirectoryOutput(local, clock);
            case ProvidedStreamChannel ignored -> new ProvidedStreamOutput(providedOutput);
            case SftpChannel sftp -> new SftpOutput(sftp, secrets, clock);
        };
    }
}
