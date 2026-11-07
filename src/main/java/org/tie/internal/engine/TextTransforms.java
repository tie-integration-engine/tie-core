package org.tie.internal.engine;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.tie.config.TextTransform;

/** Applies {@link TextTransform}s in order (§4.3 step 4). */
final class TextTransforms {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private TextTransforms() {
    }

    static String apply(List<TextTransform> transforms, String value) {
        String result = value;
        for (TextTransform transform : transforms) {
            result = apply(transform, result);
        }
        return result;
    }

    static String apply(TextTransform transform, String value) {
        return switch (transform) {
            case TextTransform.Upper ignored -> value.toUpperCase(Locale.ROOT);
            case TextTransform.Lower ignored -> value.toLowerCase(Locale.ROOT);
            case TextTransform.StripAccents ignored ->
                    MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
            case TextTransform.PadLeft(int length, char padChar) -> pad(value, length, padChar, true);
            case TextTransform.PadRight(int length, char padChar) -> pad(value, length, padChar, false);
        };
    }

    /** Pads up to {@code length} code points; longer values are left untouched (AC-47). */
    private static String pad(String value, int length, char padChar, boolean left) {
        int missing = length - value.codePointCount(0, value.length());
        if (missing <= 0) {
            return value;
        }
        String padding = String.valueOf(padChar).repeat(missing);
        return left ? padding + value : value + padding;
    }
}
