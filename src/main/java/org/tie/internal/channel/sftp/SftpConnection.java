package org.tie.internal.channel.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tie.FileErrorType;
import org.tie.config.SftpChannel;
import org.tie.internal.channel.ChannelException;
import org.tie.internal.secret.SecretReference;
import org.tie.spi.SecretProvider;

/**
 * One SFTP session (JSch, mwiede fork) for a channel. Host keys are always verified unless
 * {@code insecureAcceptAnyHostKey}, which logs a WARN on every connection (AC-101). Credentials are
 * resolved only to connect and cleared afterwards; no message ever contains them (INV-10).
 */
final class SftpConnection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SftpConnection.class);

    private final Session session;
    private final ChannelSftp sftp;
    private final String description;
    private final java.time.Duration timeout;

    private SftpConnection(Session session, ChannelSftp sftp, String description, java.time.Duration timeout) {
        this.session = session;
        this.sftp = sftp;
        this.description = description;
        this.timeout = timeout;
    }

    static String description(SftpChannel channel) {
        return "sftp://" + channel.username() + "@" + channel.host() + ":" + channel.port() + channel.remoteDirectory();
    }

    static SftpConnection open(SftpChannel channel, SecretProvider secrets) throws ChannelException {
        String description = description(channel);
        List<char[]> resolved = new ArrayList<>();
        Session session = null;
        try {
            JSch jsch = new JSch();
            int timeout = (int) Math.min(Integer.MAX_VALUE, channel.timeout().toMillis());
            if (channel.privateKeyFile() != null || channel.privateKey() != null) {
                byte[] passphrase = channel.privateKeyPassphrase() == null ? null
                        : bytes(secret(channel.privateKeyPassphrase(), secrets, resolved));
                if (channel.privateKeyFile() != null) {
                    jsch.addIdentity(channel.privateKeyFile().toString(), passphrase);
                } else {
                    jsch.addIdentity("tie-key", bytes(secret(channel.privateKey(), secrets, resolved)), null, passphrase);
                }
                clear(passphrase);
            }
            session = jsch.getSession(channel.username(), channel.host(), channel.port());
            if (channel.password() != null) {
                byte[] password = bytes(secret(channel.password(), secrets, resolved));
                session.setPassword(password);
                clear(password);
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            } else {
                session.setConfig("PreferredAuthentications", "publickey");
            }
            hostKeys(jsch, session, channel, description);
            session.setTimeout(timeout);
            session.setServerAliveInterval(timeout);
            session.setServerAliveCountMax(2);
            session.connect(timeout);
            ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(timeout);
            return new SftpConnection(session, sftp, description, channel.timeout());
        } catch (JSchException e) {
            if (session != null) {
                session.disconnect();
            }
            throw classify(e, description);
        } catch (RuntimeException e) {
            if (session != null) {
                session.disconnect();
            }
            // the message of a SecretProvider failure could contain a secret: never propagate it
            throw new ChannelException(FileErrorType.AUTHENTICATION, "cannot resolve the credentials of "
                    + description, null);
        } finally {
            resolved.forEach(secret -> Arrays.fill(secret, '\0'));
        }
    }

    private static void hostKeys(JSch jsch, Session session, SftpChannel channel, String description)
            throws JSchException {
        if (channel.insecureAcceptAnyHostKey()) {
            LOG.warn("insecureAcceptAnyHostKey is enabled for {}: the identity of the server is not verified",
                    description);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setHostKeyRepository(new FixedRepository(null));
        } else if (channel.hostKeyFingerprint() != null) {
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setHostKeyRepository(new FixedRepository(channel.hostKeyFingerprint()));
        } else {
            session.setConfig("StrictHostKeyChecking", "yes");
            jsch.setKnownHosts(channel.knownHostsFile().toString());
        }
    }

    ChannelSftp sftp() {
        return sftp;
    }

    /** A watchdog for one transfer; pass it as the progress monitor of {@code get}/{@code put}. */
    TransferWatchdog watchdog() {
        return new TransferWatchdog(session, timeout);
    }

    java.time.Duration timeout() {
        return timeout;
    }

    String description() {
        return description;
    }

    /** Whether the remote path exists. */
    boolean exists(String path) throws SftpException {
        return stat(path) != null;
    }

    /** The attributes of a path, or null if it does not exist. */
    SftpATTRS stat(String path) throws SftpException {
        try {
            return sftp.stat(path);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public void close() {
        sftp.disconnect();
        session.disconnect();
    }

    // ---------------------------------------------------------------- errors and secrets

    static ChannelException classify(Exception e, String description) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("auth fail") || lower.contains("auth cancel") || lower.contains("userauth")) {
            return new ChannelException(FileErrorType.AUTHENTICATION, "authentication failed for " + description, null);
        }
        if (lower.contains("hostkey") || lower.contains("host key")) {
            return new ChannelException(FileErrorType.HOST_KEY, "host key verification failed for " + description, null);
        }
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return new ChannelException("cannot connect to " + description + ": " + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()), null);
    }

    private static char[] secret(String value, SecretProvider secrets, List<char[]> resolved) {
        char[] secret = SecretReference.name(value).map(name -> {
            char[] provided = secrets == null ? null : secrets.secret(name);
            if (provided == null) {
                throw new IllegalStateException("secret '" + name + "' is not available");
            }
            return provided;
        }).orElseGet(value::toCharArray);
        resolved.add(secret);
        return secret;
    }

    private static byte[] bytes(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Arrays.fill(buffer.array(), (byte) 0);
        return bytes;
    }

    private static void clear(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** SHA256 fingerprint in OpenSSH format, without padding: {@code SHA256:base64}. */
    static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String normalizeFingerprint(String fingerprint) {
        String value = fingerprint.strip();
        if (!value.regionMatches(true, 0, "SHA256:", 0, 7)) {
            value = "SHA256:" + value;
        }
        value = "SHA256:" + value.substring(7);
        while (value.endsWith("=")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** Accepts one fingerprint, or any key when {@code fingerprint} is null (insecure mode). */
    private static final class FixedRepository implements HostKeyRepository {

        private final String fingerprint;

        FixedRepository(String fingerprint) {
            this.fingerprint = fingerprint == null ? null : normalizeFingerprint(fingerprint);
        }

        @Override
        public int check(String host, byte[] key) {
            if (fingerprint == null) {
                return OK;
            }
            return fingerprint(key).equals(fingerprint) ? OK : CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
            // never stored
        }

        @Override
        public void remove(String host, String type) {
            // nothing stored
        }

        @Override
        public void remove(String host, String type, byte[] key) {
            // nothing stored
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "tie-fingerprint";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }
}
