package org.tie.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A field of a layout (§4.1). Build it with {@link #builder(String)}.
 *
 * <p>{@code onEmpty} defaults to {@link OnEmpty#DEFAULT} when there is a {@code defaultValue} and to
 * {@link OnEmpty#EMPTY} otherwise. {@code outputAs == null} means "derived" (see
 * {@link #effectiveOutputAs()}). {@code copyMode == null} means not declared, which behaves as
 * {@link CopyMode#TEXT} (see {@link #effectiveCopyMode()}). {@code defaultMode == null} means not declared, which
 * behaves as {@link DefaultMode#CONVERT} (see {@link #effectiveDefaultMode()}).
 */
public record FieldDefinition(
        String name,
        String column,
        boolean inInput,
        boolean optional,
        String copyFrom,
        CopyMode copyMode,
        List<TextTransform> textTransforms,
        List<Translation> translations,
        OnUnmatched onUnmatched,
        String transformer,
        FieldType type,
        TypeFormat typeFormat,
        Validations validations,
        OnEmpty onEmpty,
        String defaultValue,
        DefaultMode defaultMode,
        String expression,
        OutputAs outputAs) {

    public FieldDefinition {
        textTransforms = textTransforms == null ? List.of() : List.copyOf(textTransforms);
        translations = translations == null ? List.of() : List.copyOf(translations);
        onUnmatched = onUnmatched == null ? OnUnmatched.ERROR : onUnmatched;
        type = type == null ? FieldType.STRING : type;
        validations = validations == null ? Validations.NONE : validations;
        onEmpty = onEmpty == null ? (defaultValue != null ? OnEmpty.DEFAULT : OnEmpty.EMPTY) : onEmpty;
    }

    public static Builder builder(String name) {
        return new Builder().name(name);
    }

    /** Whether any step turns the value into text on export, which forces {@link OutputAs#TEXT}. */
    public boolean hasTextualExportSteps() {
        return typeFormat != null || !textTransforms.isEmpty() || !translations.isEmpty() || transformer != null;
    }

    /** {@code copyMode} if declared, else {@link CopyMode#TEXT}. */
    public CopyMode effectiveCopyMode() {
        return copyMode == null ? CopyMode.TEXT : copyMode;
    }

    /** {@code defaultMode} if declared, else {@link DefaultMode#CONVERT}. */
    public DefaultMode effectiveDefaultMode() {
        return defaultMode == null ? DefaultMode.CONVERT : defaultMode;
    }

    /** {@code outputAs} if set; otherwise TEXT when {@link #hasTextualExportSteps()}, else NATIVE. */
    public OutputAs effectiveOutputAs() {
        if (outputAs != null) {
            return outputAs;
        }
        return hasTextualExportSteps() ? OutputAs.TEXT : OutputAs.NATIVE;
    }

    public Builder toBuilder() {
        return new Builder().name(name).column(column).inInput(inInput).optional(optional).copyFrom(copyFrom)
                .copyMode(copyMode).textTransforms(textTransforms).translations(translations).onUnmatched(onUnmatched)
                .transformer(transformer).type(type).typeFormat(typeFormat).validations(validations)
                .onEmpty(onEmpty).defaultValue(defaultValue).defaultMode(defaultMode).expression(expression).outputAs(outputAs);
    }

    public static final class Builder {
        private String name;
        private String column;
        private boolean inInput = true;
        private boolean optional;
        private String copyFrom;
        private CopyMode copyMode;
        private final List<TextTransform> textTransforms = new ArrayList<>();
        private final List<Translation> translations = new ArrayList<>();
        private OnUnmatched onUnmatched;
        private String transformer;
        private FieldType type;
        private TypeFormat typeFormat;
        private Validations validations;
        private OnEmpty onEmpty;
        private String defaultValue;
        private DefaultMode defaultMode;
        private String expression;
        private OutputAs outputAs;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder column(String column) {
            this.column = column;
            return this;
        }

        public Builder inInput(boolean inInput) {
            this.inInput = inInput;
            return this;
        }

        public Builder optional(boolean optional) {
            this.optional = optional;
            return this;
        }

        public Builder copyFrom(String copyFrom) {
            this.copyFrom = copyFrom;
            return this;
        }

        public Builder copyMode(CopyMode copyMode) {
            this.copyMode = copyMode;
            return this;
        }

        public Builder textTransform(TextTransform textTransform) {
            this.textTransforms.add(textTransform);
            return this;
        }

        public Builder textTransforms(List<TextTransform> textTransforms) {
            this.textTransforms.clear();
            this.textTransforms.addAll(textTransforms);
            return this;
        }

        public Builder translation(Translation translation) {
            this.translations.add(translation);
            return this;
        }

        public Builder translation(String from, String to) {
            return translation(Translation.of(from, to));
        }

        public Builder translations(List<Translation> translations) {
            this.translations.clear();
            this.translations.addAll(translations);
            return this;
        }

        public Builder onUnmatched(OnUnmatched onUnmatched) {
            this.onUnmatched = onUnmatched;
            return this;
        }

        public Builder transformer(String transformer) {
            this.transformer = transformer;
            return this;
        }

        public Builder type(FieldType type) {
            this.type = type;
            return this;
        }

        public Builder typeFormat(TypeFormat typeFormat) {
            this.typeFormat = typeFormat;
            return this;
        }

        public Builder validations(Validations validations) {
            this.validations = validations;
            return this;
        }

        public Builder onEmpty(OnEmpty onEmpty) {
            this.onEmpty = onEmpty;
            return this;
        }

        public Builder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder defaultMode(DefaultMode defaultMode) {
            this.defaultMode = defaultMode;
            return this;
        }

        /** The source of an expression that computes the field's final value (§4.3 «Expresiones»). */
        public Builder expression(String expression) {
            this.expression = expression;
            return this;
        }

        public Builder outputAs(OutputAs outputAs) {
            this.outputAs = outputAs;
            return this;
        }

        public FieldDefinition build() {
            return new FieldDefinition(name, column, inInput, optional, copyFrom, copyMode, textTransforms, translations,
                    onUnmatched, transformer, type, typeFormat, validations, onEmpty, defaultValue, defaultMode, expression, outputAs);
        }
    }
}
