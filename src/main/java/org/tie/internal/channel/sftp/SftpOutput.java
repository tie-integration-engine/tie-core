package org.tie.internal.channel.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Consumer;
import org.tie.FileErrorType;
import org.tie.Warning;
import org.tie.WarningCode;
import org.tie.config.OnExistingFile;
import org.tie.config.SftpChannel;
import org.tie.internal.channel.ChannelException;
import org.tie.internal.channel.FileNames;
import org.tie.internal.delivery.Delivered;
import org.tie.internal.delivery.OutputChannel;
import org.tie.spi.SecretProvider;

/**
 * Delivery to an SFTP directory (§4.1 "Entrega"): upload to {@code .<name>.part}, then rename. SFTP
 * rename never replaces, so {@code OVERWRITE} removes the existing file first; it is therefore not
 * atomic.
 */
public final class SftpOutput implements OutputChannel {

    private final SftpChannel channel;
    private final SecretProvider secrets;
    private final Clock clock;
    private SftpConnection connection;

    public SftpOutput(SftpChannel channel, SecretProvider secrets, Clock clock) {
        this.channel = channel;
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public Delivered deliver(Path file, String name, OnExistingFile onExistingFile, Consumer<Warning> warnings)
            throws ChannelException {
        if (connection == null) {
            connection = SftpConnection.open(channel, secrets);
        }
        ChannelSftp sftp = connection.sftp();
        String directory = channel.remoteDirectory();
        String temporary = SftpPaths.join(directory, "." + name + ".part");
        TransferWatchdog watchdog = connection.watchdog();
        try (watchdog; InputStream in = Files.newInputStream(file)) {
            sftp.put(in, temporary, watchdog, ChannelSftp.OVERWRITE);
        } catch (SftpException | IOException e) {
            removeQuietly(temporary, name, warnings);
            throw new ChannelException((watchdog.expired() ? "timeout: no progress for " + connection.timeout()
                    + " uploading " : "cannot upload ") + name + " to " + connection.description() + ": "
                    + e.getMessage(), e);
        }
        try {
            String finalName = name;
            if (connection.exists(SftpPaths.join(directory, name))) {
                switch (onExistingFile) {
                    case FAIL -> {
                        removeQuietly(temporary, name, warnings);
                        throw collision(name);
                    }
                    case OVERWRITE -> sftp.rm(SftpPaths.join(directory, name));
                    case SUFFIX -> finalName = FileNames.withSuffix(name, clock.millis());
                }
            }
            try {
                sftp.rename(temporary, SftpPaths.join(directory, finalName));
            } catch (SftpException e) {
                if (!connection.exists(SftpPaths.join(directory, finalName))) {
                    throw e;
                }
                if (onExistingFile == OnExistingFile.FAIL) {
                    removeQuietly(temporary, name, warnings);
                    throw collision(name);
                }
                finalName = FileNames.withSuffix(name, clock.millis());
                sftp.rename(temporary, SftpPaths.join(directory, finalName));
            }
            return new Delivered(finalName, connection.description());
        } catch (SftpException e) {
            removeQuietly(temporary, name, warnings);
            throw new ChannelException("cannot rename " + temporary + " to " + name + " in " + connection.description()
                    + ": " + e.getMessage(), e);
        }
    }

    private ChannelException collision(String name) {
        return new ChannelException(FileErrorType.NAME_COLLISION, name + " already exists in "
                + SftpConnection.description(channel), null);
    }

    private void removeQuietly(String temporary, String name, Consumer<Warning> warnings) {
        try {
            if (connection.exists(temporary)) {
                connection.sftp().rm(temporary);
            }
        } catch (SftpException | RuntimeException e) {
            warnings.accept(new Warning(WarningCode.TEMP_CLEANUP_FAILED, "cannot delete temporary file " + temporary
                    + " in " + SftpConnection.description(channel), name));
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
