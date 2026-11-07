package org.tie.spi;

/** Resolves {@code ${secret:name}} placeholders; TIE clears the returned array after use. */
@FunctionalInterface
public interface SecretProvider {

    char[] secret(String name);
}
