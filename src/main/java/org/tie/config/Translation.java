package org.tie.config;

/**
 * One row of a field's translation table. {@code to == null} translates to an empty value.
 * {@code direction} defaults to {@link TranslationDirection#BOTH}; on export, BOTH rows are inverted.
 */
public record Translation(String from, String to, TranslationDirection direction) {

    public Translation {
        direction = direction == null ? TranslationDirection.BOTH : direction;
    }

    public static Translation of(String from, String to) {
        return new Translation(from, to, TranslationDirection.BOTH);
    }
}
