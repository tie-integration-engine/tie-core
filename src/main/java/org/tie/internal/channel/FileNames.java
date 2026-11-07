package org.tie.internal.channel;

/** File name helpers shared by channels and the run. */
public final class FileNames {

    private FileNames() {
    }

    /** Whether a listed name is safe: no {@code /}, {@code \} or {@code ..} (AC-20). */
    public static boolean isSafe(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /** The name without its last extension. */
    public static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** The last extension without the dot, or "" if there is none. */
    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1);
    }

    /** {@code name.<millis>.ext}, the collision suffix of a move or a delivery (AC-84). */
    public static String withSuffix(String name, long millis) {
        String extension = extension(name);
        return baseName(name) + "." + millis + (extension.isEmpty() ? "" : "." + extension);
    }
}
