package org.tie.internal.channel.sftp;

/** Remote path helpers (always {@code /}). */
final class SftpPaths {

    private SftpPaths() {
    }

    static String join(String directory, String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    /** {@code folder} relative to {@code directory} unless it starts with {@code /} (AC-193). */
    static String resolve(String directory, String folder) {
        String target = folder.startsWith("/") ? folder : join(directory, folder);
        return target.length() > 1 && target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
    }
}
