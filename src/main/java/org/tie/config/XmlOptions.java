package org.tie.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** XML options. {@code recordElement} is required for a layout that reads or writes XML. */
public record XmlOptions(String recordElement, String rootElement, Charset encoding) {

    public static final XmlOptions DEFAULTS = new XmlOptions(null, null, null);

    public XmlOptions {
        rootElement = rootElement == null ? "records" : rootElement;
        encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
    }

    public static XmlOptions of(String recordElement) {
        return new XmlOptions(recordElement, null, null);
    }
}
