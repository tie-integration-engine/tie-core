package org.tie;

import java.util.List;
import java.util.Map;

/**
 * Stable catalog of line error codes (§5.4, INV-18). The code and its argument names are the
 * contract; the English message may change between versions.
 */
public enum ErrorCode {
    NO_TRANSLATION("value '{value}' has no translation", "value"),
    REQUIRED_EMPTY("required field is empty"),
    INVALID_TYPE("value '{value}' is not a valid {type}", "value", "type", "format"),
    TOO_LONG("value is longer than {maxLength}", "value", "maxLength"),
    TOO_SHORT("value is shorter than {minLength}", "value", "minLength"),
    PATTERN_MISMATCH("value '{value}' does not match {regex}", "value", "regex"),
    NOT_ALLOWED("value '{value}' is not allowed", "value", "allowedValues"),
    DEFAULT_UNRESOLVABLE("default value has placeholders and no resolver is registered", "template"),
    DEFAULT_RESOLVER_FAILED("default value resolver failed: {exceptionMessage}", "template", "exceptionMessage"),
    TRANSFORMER_FAILED("transformer '{transformer}' failed: {exceptionMessage}", "transformer", "exceptionMessage"),
    EXPRESSION_FAILED("expression failed: {exceptionMessage}", "exceptionMessage"),
    PARENT_REJECTED("parent rejected", "parentLineNumber"),
    CONSUMER_REJECTED("{reason}", "reason"),
    CONSUMER_FAILED("{exceptionMessage}", "exceptionMessage");

    private final String template;
    private final List<String> argumentNames;

    ErrorCode(String template, String... argumentNames) {
        this.template = template;
        this.argumentNames = List.of(argumentNames);
    }

    /** Every argument name this code may carry; an argument without a value is omitted. */
    public List<String> argumentNames() {
        return argumentNames;
    }

    /** Whether errors with this code belong to a field. */
    public boolean isFieldError() {
        return this != PARENT_REJECTED && this != CONSUMER_REJECTED && this != CONSUMER_FAILED;
    }

    /** The English message with the arguments filled in. */
    public String message(Map<String, String> args) {
        String message = template;
        for (String name : argumentNames) {
            String value = args.get(name);
            if (value != null) {
                message = message.replace("{" + name + "}", value);
            }
        }
        return message;
    }
}
