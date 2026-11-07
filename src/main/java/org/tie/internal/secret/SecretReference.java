package org.tie.internal.secret;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code ${secret:name}} configuration values (§5.5). */
public final class SecretReference {

    private static final String PREFIX = "${secret:";
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]+)}");

    private SecretReference() {
    }

    /** Whether the value is meant to be a secret reference (starts with {@code ${secret:}). */
    public static boolean looksLikeReference(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** The secret name if the value is a well-formed reference. */
    public static Optional<String> name(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = REFERENCE.matcher(value);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Whether the value starts like a reference but is not a valid one. */
    public static boolean isMalformed(String value) {
        return looksLikeReference(value) && name(value).isEmpty();
    }
}
