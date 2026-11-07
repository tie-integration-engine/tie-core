package org.tie.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.tie.config.SftpChannel;

/**
 * An embedded SFTP server (Apache MINA SSHD) rooted at a temporary directory, with password and
 * public key authentication, and hooks to cut uploads or slow down downloads.
 */
public final class SftpServer implements AutoCloseable {

    public static final String USER = "tie";
    public static final String PASSWORD = "s3cr3t-Pa55";

    private final SshServer sshd;
    private final Path root;
    private final Path knownHosts;
    private final SimpleGeneratorHostKeyProvider hostKeys;
    private volatile PublicKey authorizedKey;
    private volatile boolean cutUploads;
    private volatile Duration readDelay = Duration.ZERO;

    public SftpServer(Path directory) throws IOException {
        this.root = Files.createDirectories(directory.resolve("sftp-root"));
        Files.createDirectories(root.resolve("in"));
        Files.createDirectories(root.resolve("out"));
        this.hostKeys = new SimpleGeneratorHostKeyProvider(directory.resolve("hostkey.ser"));
        hostKeys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        this.sshd = SshServer.setUpDefaultServer();
        sshd.setHost("localhost");
        sshd.setPort(0);
        sshd.setKeyPairProvider(hostKeys);
        sshd.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
        sshd.setPublickeyAuthenticator((user, key, session) ->
                USER.equals(user) && authorizedKey != null && KeyUtils.compareKeys(authorizedKey, key));
        SftpSubsystemFactory sftp = new SftpSubsystemFactory.Builder().build();
        sftp.addSftpEventListener(new SftpEventListener() {
            @Override
            public void written(ServerSession session, String remoteHandle, FileHandle localHandle, long offset,
                                byte[] data, int dataOffset, int dataLen, Throwable thrown) {
                if (cutUploads && localHandle.getFile().getFileName().toString().endsWith(".part")) {
                    session.close(true);
                }
            }

            @Override
            public void reading(ServerSession session, String remoteHandle, FileHandle localHandle, long offset,
                                byte[] data, int dataOffset, int dataLen) {
                if (!readDelay.isZero()) {
                    try {
                        Thread.sleep(readDelay.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        sshd.setSubsystemFactories(List.of(sftp));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        sshd.start();
        this.knownHosts = directory.resolve("known_hosts");
        Files.writeString(knownHosts, "[localhost]:" + port() + " " + PublicKeyEntry.toString(hostKey()) + "\n");
    }

    public int port() {
        return sshd.getPort();
    }

    public Path root() {
        return root;
    }

    public Path in() {
        return root.resolve("in");
    }

    public Path out() {
        return root.resolve("out");
    }

    public Path knownHosts() {
        return knownHosts;
    }

    public PublicKey hostKey() {
        try {
            return hostKeys.loadKeys(null).iterator().next().getPublic();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The host key fingerprint as OpenSSH prints it ({@code SHA256:…}). */
    public String fingerprint() {
        return KeyUtils.getFingerPrint(hostKey());
    }

    public void authorize(PublicKey key) {
        this.authorizedKey = key;
    }

    public void cutUploads(boolean cut) {
        this.cutUploads = cut;
    }

    public void delayReads(Duration delay) {
        this.readDelay = delay;
    }

    /** A channel on {@code remoteDirectory} with password authentication and known_hosts verification. */
    public SftpChannel.Builder channel(String remoteDirectory) {
        return SftpChannel.builder()
                .host("localhost")
                .port(port())
                .username(USER)
                .password(PASSWORD)
                .remoteDirectory(remoteDirectory)
                .knownHostsFile(knownHosts)
                .timeout(Duration.ofSeconds(10));
    }

    public void stop() throws IOException {
        sshd.stop(true);
    }

    @Override
    public void close() throws IOException {
        stop();
    }
}
