package org.tie.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * JSON options (§4.1). {@code recordProperty == null} means the records are the root array or, with
 * {@code firstArray} and a root object, the first top-level property whose value is an array. {@code firstArray}
 * only applies to reading and cannot be combined with {@code recordProperty} on import. Build it with
 * {@link #builder()}.
 */
public record JsonOptions(String recordProperty, Charset encoding, boolean firstArray) {

    public static final JsonOptions DEFAULTS = builder().build();

    public JsonOptions {
        encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().recordProperty(recordProperty).encoding(encoding).firstArray(firstArray);
    }

    public static final class Builder {
        private String recordProperty;
        private Charset encoding = StandardCharsets.UTF_8;
        private boolean firstArray;

        private Builder() {
        }

        public Builder recordProperty(String recordProperty) {
            this.recordProperty = recordProperty;
            return this;
        }

        public Builder encoding(Charset encoding) {
            this.encoding = encoding;
            return this;
        }

        public Builder firstArray(boolean firstArray) {
            this.firstArray = firstArray;
            return this;
        }

        public JsonOptions build() {
            return new JsonOptions(recordProperty, encoding, firstArray);
        }
    }
}
