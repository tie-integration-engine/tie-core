package org.tie.config;

/** A text transformation applied to a field value (§4.3 step 4, §4.4 step 5). */
public sealed interface TextTransform {

    TextTransform UPPER = new Upper();
    TextTransform LOWER = new Lower();
    TextTransform STRIP_ACCENTS = new StripAccents();

    static TextTransform padLeft(int length, char padChar) {
        return new PadLeft(length, padChar);
    }

    static TextTransform padRight(int length, char padChar) {
        return new PadRight(length, padChar);
    }

    /** Upper case (root locale). */
    record Upper() implements TextTransform {
    }

    /** Lower case (root locale). */
    record Lower() implements TextTransform {
    }

    /** NFD decomposition without diacritical marks: {@code ñ → n}. */
    record StripAccents() implements TextTransform {
    }

    /** Left padding up to {@code length}; longer values are left untouched. */
    record PadLeft(int length, char padChar) implements TextTransform {
    }

    /** Right padding up to {@code length}; longer values are left untouched. */
    record PadRight(int length, char padChar) implements TextTransform {
    }
}
