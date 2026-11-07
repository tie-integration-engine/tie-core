package org.tie.internal.channel.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.tie.RemoteFile;
import org.tie.config.SftpChannel;
import org.tie.internal.channel.ChannelException;
import org.tie.internal.channel.FileNames;
import org.tie.internal.channel.InputChannel;
import org.tie.internal.channel.LocalCopy;
import org.tie.spi.SecretProvider;

/**
 * An SFTP input channel. The session opens when listing and stays open for the run; each file is
 * downloaded entirely into the working directory before it is read (§5.3).
 */
public final class SftpInput implements InputChannel {

    private final SftpChannel channel;
    private final SecretProvider secrets;
    private SftpConnection connection;

    public SftpInput(SftpChannel channel, SecretProvider secrets) {
        this.channel = channel;
        this.secrets = secrets;
    }

    @Override
    public String description() {
        return SftpConnection.description(channel);
    }

    private SftpConnection connection() throws ChannelException {
        if (connection == null) {
            connection = SftpConnection.open(channel, secrets);
        }
        return connection;
    }

    @Override
    public List<RemoteFile> list() throws ChannelException {
        ChannelSftp sftp = connection().sftp();
        List<RemoteFile> files = new ArrayList<>();
        try {
            for (ChannelSftp.LsEntry entry : sftp.ls(channel.remoteDirectory())) {
                SftpATTRS attributes = entry.getAttrs();
                if (attributes.isReg()) {
                    files.add(new RemoteFile(entry.getFilename(), attributes.getSize(),
                            Instant.ofEpochSecond(Integer.toUnsignedLong(attributes.getMTime()))));
                }
            }
        } catch (SftpException e) {
            throw failure("cannot list " + description(), e);
        }
        files.sort(Comparator.comparing(RemoteFile::name));
        return files;
    }

    @Override
    public LocalCopy fetch(RemoteFile file, Path workingDirectory) throws ChannelException {
        Path copy;
        try {
            copy = Files.createTempFile(workingDirectory, "tie-", "-" + file.name());
        } catch (IOException e) {
            throw new ChannelException("cannot create a working copy of " + file.name() + ": " + e.getMessage(), e);
        }
        SftpConnection connection = connection();
        TransferWatchdog watchdog = connection.watchdog();
        try (watchdog; OutputStream out = Files.newOutputStream(copy)) {
            connection.sftp().get(path(file), out, watchdog);
            return new LocalCopy(copy, true);
        } catch (SftpException e) {
            deleteQuietly(copy);
            if (watchdog.expired()) {
                throw new ChannelException("timeout: no progress for " + connection.timeout() + " downloading "
                        + file.name() + " from " + description(), e);
            }
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw new ChannelException("file " + file.name() + " no longer exists in " + description(), e);
            }
            throw failure("cannot download " + file.name() + " from " + description(), e);
        } catch (IOException e) {
            deleteQuietly(copy);
            throw new ChannelException("cannot download " + file.name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void move(RemoteFile file, String folder, Clock clock) throws ChannelException {
        SftpConnection connection = connection();
        String target = SftpPaths.resolve(channel.remoteDirectory(), folder);
        try {
            SftpATTRS attributes = connection.stat(target);
            if (attributes == null || !attributes.isDir()) {
                throw new ChannelException("folder " + target + " does not exist in sftp://" + channel.host(), null);
            }
            String destination = SftpPaths.join(target, file.name());
            if (connection.exists(destination)) {
                destination = SftpPaths.join(target, FileNames.withSuffix(file.name(), clock.millis()));
            }
            connection.sftp().rename(path(file), destination);
        } catch (SftpException e) {
            throw failure("cannot move " + file.name() + " to " + target, e);
        }
    }

    @Override
    public void delete(RemoteFile file) throws ChannelException {
        try {
            connection().sftp().rm(path(file));
        } catch (SftpException e) {
            throw failure("cannot delete " + file.name() + " from " + description(), e);
        }
    }

    private String path(RemoteFile file) {
        return SftpPaths.join(channel.remoteDirectory(), file.name());
    }

    private static ChannelException failure(String message, SftpException e) {
        return new ChannelException(message + ": " + e.getMessage(), e);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the run reports working directory leftovers as TEMP_CLEANUP_FAILED
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
