package org.tie.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * An SFTP server directory, for input or output.
 *
 * <p>Exactly one of {@code password}, {@code privateKey} (PEM content) or {@code privateKeyFile} is
 * required. {@code password}, {@code privateKey} and {@code privateKeyPassphrase} accept a literal or
 * {@code ${secret:name}}. {@link #toString()} never prints them (INV-10).
 */
public record SftpChannel(
        String host,
        int port,
        String username,
        String password,
        String privateKey,
        Path privateKeyFile,
        String privateKeyPassphrase,
        String remoteDirectory,
        Path knownHostsFile,
        String hostKeyFingerprint,
        boolean insecureAcceptAnyHostKey,
        Duration timeout,
        Long maxFileSize,
        OnExistingFile onExistingFile) implements ChannelDefinition {

    public static final int DEFAULT_PORT = 22;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public SftpChannel {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        onExistingFile = onExistingFile == null ? OnExistingFile.SUFFIX : onExistingFile;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().host(host).port(port).username(username).password(password).privateKey(privateKey)
                .privateKeyFile(privateKeyFile).privateKeyPassphrase(privateKeyPassphrase)
                .remoteDirectory(remoteDirectory).knownHostsFile(knownHostsFile)
                .hostKeyFingerprint(hostKeyFingerprint).insecureAcceptAnyHostKey(insecureAcceptAnyHostKey)
                .timeout(timeout).maxFileSize(maxFileSize).onExistingFile(onExistingFile);
    }

    @Override
    public String toString() {
        return "SftpChannel[host=" + host + ", port=" + port + ", username=" + username
                + ", password=" + redacted(password) + ", privateKey=" + redacted(privateKey)
                + ", privateKeyFile=" + privateKeyFile + ", privateKeyPassphrase=" + redacted(privateKeyPassphrase)
                + ", remoteDirectory=" + remoteDirectory + ", knownHostsFile=" + knownHostsFile
                + ", hostKeyFingerprint=" + hostKeyFingerprint + ", insecureAcceptAnyHostKey=" + insecureAcceptAnyHostKey
                + ", timeout=" + timeout + ", maxFileSize=" + maxFileSize + ", onExistingFile=" + onExistingFile + "]";
    }

    private static String redacted(String secret) {
        return secret == null ? "null" : "****";
    }

    public static final class Builder {
        private String host;
        private int port = DEFAULT_PORT;
        private String username;
        private String password;
        private String privateKey;
        private Path privateKeyFile;
        private String privateKeyPassphrase;
        private String remoteDirectory;
        private Path knownHostsFile;
        private String hostKeyFingerprint;
        private boolean insecureAcceptAnyHostKey;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Long maxFileSize;
        private OnExistingFile onExistingFile = OnExistingFile.SUFFIX;

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder privateKey(String privateKey) {
            this.privateKey = privateKey;
            return this;
        }

        public Builder privateKeyFile(Path privateKeyFile) {
            this.privateKeyFile = privateKeyFile;
            return this;
        }

        public Builder privateKeyPassphrase(String privateKeyPassphrase) {
            this.privateKeyPassphrase = privateKeyPassphrase;
            return this;
        }

        public Builder remoteDirectory(String remoteDirectory) {
            this.remoteDirectory = remoteDirectory;
            return this;
        }

        public Builder knownHostsFile(Path knownHostsFile) {
            this.knownHostsFile = knownHostsFile;
            return this;
        }

        public Builder hostKeyFingerprint(String hostKeyFingerprint) {
            this.hostKeyFingerprint = hostKeyFingerprint;
            return this;
        }

        public Builder insecureAcceptAnyHostKey(boolean insecureAcceptAnyHostKey) {
            this.insecureAcceptAnyHostKey = insecureAcceptAnyHostKey;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxFileSize(Long maxFileSize) {
            this.maxFileSize = maxFileSize;
            return this;
        }

        public Builder onExistingFile(OnExistingFile onExistingFile) {
            this.onExistingFile = onExistingFile;
            return this;
        }

        public SftpChannel build() {
            return new SftpChannel(host, port, username, password, privateKey, privateKeyFile, privateKeyPassphrase,
                    remoteDirectory, knownHostsFile, hostKeyFingerprint, insecureAcceptAnyHostKey, timeout,
                    maxFileSize, onExistingFile);
        }
    }
}
