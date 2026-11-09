package org.tie.internal.format.tree;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.tie.FileErrorType;
import org.tie.config.Layout;
import org.tie.config.XmlOptions;
import org.tie.internal.format.DecodingReader;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.NestedRecords;
import org.tie.internal.format.ReadRecord;
import org.tie.internal.format.RecordReader;
import org.tie.internal.format.SecureXml;

/**
 * Records of an XML file: every {@code recordElement} that is not inside another record or, without
 * {@code recordElement}, every child of the root element whatever its name (text, comments and processing
 * instructions directly under the root are ignored). Elements
 * use their local name (namespaces are ignored), attributes are {@code @name}, the text of an
 * element with children is ignored, and a repeated element that is not a nested layout is a
 * structural error (AC-160). A DOCTYPE fails the file without being processed (AC-35).
 */
public final class XmlRecordReader implements RecordReader {

    private final XMLStreamReader xml;
    private final String recordElement;
    private final Layout root;
    private final TreeLayouts layouts;
    private boolean finished;
    /** Element depth (the root element is 1); only used without {@code recordElement}. */
    private int depth;

    public XmlRecordReader(InputStream in, XmlOptions options, Layout root, List<Layout> layouts) {
        this.recordElement = options.recordElement();
        this.root = root;
        this.layouts = new TreeLayouts(layouts);
        try {
            this.xml = SecureXml.inputFactory().createXMLStreamReader(new DecodingReader(in, options.encoding()));
        } catch (XMLStreamException e) {
            throw failure(e);
        }
    }

    @Override
    public ReadRecord next() {
        if (finished) {
            return null;
        }
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.DTD) {
                    throw new FileFailure(FileErrorType.UNSAFE_CONTENT,
                            "XML with a DOCTYPE is not accepted (DTDs and external entities are disabled)");
                }
                if (recordElement == null) {
                    if (event == XMLStreamConstants.START_ELEMENT && ++depth == 2) {
                        depth--;
                        return record(element(), root);
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    }
                } else if (event == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals(recordElement)) {
                    return record(element(), root);
                }
            }
            finished = true;
            return null;
        } catch (XMLStreamException e) {
            throw failure(e);
        }
    }

    /** Reads the current element and its subtree. */
    private Element element() throws XMLStreamException {
        Element element = new Element(xml.getLocalName());
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            element.attributes.put(xml.getAttributeLocalName(i), xml.getAttributeValue(i));
        }
        while (xml.hasNext()) {
            int event = xml.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> element.children.add(element());
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        element.text.append(xml.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    return element;
                }
                default -> {
                }
            }
        }
        throw FileFailure.structural("unexpected end of XML inside <" + element.name + ">", null);
    }

    private ReadRecord record(Element element, Layout layout) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Layout> childLayouts = layouts.children(layout);
        Map<String, NestedRecords> children = new LinkedHashMap<>();
        attributes(element, "", values);
        flatten(element, "", values, childLayouts, children);
        List<NestedRecords> nested = new ArrayList<>();
        for (Map.Entry<String, Layout> child : childLayouts.entrySet()) {
            nested.add(children.getOrDefault(child.getKey(), new NestedRecords(child.getValue(), false, List.of())));
        }
        return new ReadRecord(TreeLayouts.input(layout, values), null, nested);
    }

    private void flatten(Element parent, String prefix, Map<String, String> values, Map<String, Layout> childLayouts,
                         Map<String, NestedRecords> children) {
        Map<String, Integer> counts = new HashMap<>();
        for (Element child : parent.children) {
            counts.merge(child.name, 1, Integer::sum);
        }
        for (Element child : parent.children) {
            String path = TreeLayouts.path(prefix, child.name);
            if (counts.get(child.name) > 1) {
                throw FileFailure.structural("element '" + path + "' is repeated and is not declared as a nested"
                        + " layout", null);
            }
            Layout childLayout = childLayouts.get(path);
            if (childLayout != null) {
                List<ReadRecord> records = new ArrayList<>();
                for (Element item : child.children) {
                    records.add(record(item, childLayout));
                }
                children.put(path, new NestedRecords(childLayout, true, records));
                continue;
            }
            attributes(child, path, values);
            if (child.children.isEmpty()) {
                values.put(path, child.text.toString());
            } else {
                flatten(child, path, values, childLayouts, children);
            }
        }
    }

    private static void attributes(Element element, String prefix, Map<String, String> values) {
        element.attributes.forEach((name, value) -> values.put(TreeLayouts.path(prefix, "@" + name), value));
    }

    private FileFailure failure(XMLStreamException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DecodingReader.EncodingException encoding) {
                return new FileFailure(FileErrorType.ENCODING, encoding.getMessage(), null, e);
            }
        }
        Location location = e.getLocation();
        String where = location == null ? "" : " (line " + location.getLineNumber() + ", column "
                + location.getColumnNumber() + ")";
        String message = e.getMessage() == null ? "" : e.getMessage().replaceFirst("(?s)^ParseError at \\[.*?]\\s*Message:\\s*", "");
        return new FileFailure(FileErrorType.STRUCTURAL, "invalid XML: " + message + where, null, e);
    }

    @Override
    public void close() {
        try {
            xml.close();
        } catch (XMLStreamException ignored) {
            // closing only
        }
    }

    private static final class Element {
        final String name;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<Element> children = new ArrayList<>();
        final StringBuilder text = new StringBuilder();

        Element(String name) {
            this.name = name;
        }
    }
}
