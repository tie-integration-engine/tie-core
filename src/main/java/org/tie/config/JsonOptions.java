package org.tie.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** JSON options; {@code recordProperty == null} means the records are the root array. */
public record JsonOptions(String recordProperty, Charset encoding) {

    public static final JsonOptions DEFAULTS = new JsonOptions(null, null);

    public JsonOptions {
        encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
    }
}
