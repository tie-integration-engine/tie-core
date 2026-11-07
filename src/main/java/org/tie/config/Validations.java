package org.tie.config;

import java.util.List;

/** Declarative validations of a non-empty field value; null members are not checked. */
public record Validations(Integer minLength, Integer maxLength, String regex, List<String> allowedValues) {

    public static final Validations NONE = new Validations(null, null, null, null);

    public Validations {
        allowedValues = allowedValues == null ? null : List.copyOf(allowedValues);
    }

    public static Validations maxLength(int maxLength) {
        return new Validations(null, maxLength, null, null);
    }

    public static Validations minLength(int minLength) {
        return new Validations(minLength, null, null, null);
    }

    public static Validations regex(String regex) {
        return new Validations(null, null, regex, null);
    }

    public static Validations allowedValues(List<String> allowedValues) {
        return new Validations(null, null, null, allowedValues);
    }
}
