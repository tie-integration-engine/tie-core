package org.tie.internal.format.write;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;
import org.tie.config.XmlOptions;
import org.tie.internal.engine.Values;

/**
 * XML export: {@code recordElement} elements inside {@code rootElement}. Each field is an element
 * (dotted paths nest), {@code @x} an attribute; an empty value is an empty element or an omitted
 * attribute. A nested layout is a container element at its {@code nestedKey} holding one element per
 * child, named by the child's xml {@code recordElement} or else its layout name.
 */
final class XmlRecordWriter implements RecordWriter {

    private final Writer out;
    private final XMLStreamWriter xml;
    private final Layout layout;

    XmlRecordWriter(OutputStream stream, Layout layout) {
        XmlOptions options = layout.formatOptions().xml();
        this.out = new OutputStreamWriter(stream, options.encoding());
        this.layout = layout;
        try {
            this.xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            xml.writeStartDocument(options.encoding().name(), "1.0");
            xml.writeStartElement(options.rootElement());
        } catch (XMLStreamException e) {
            throw new IllegalStateException("cannot start the XML document: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(OutputRecord record) {
        Element element = element(layout.formatOptions().xml().recordElement(), record, layout);
        try {
            element.write(xml);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("cannot write XML: " + e.getMessage(), e);
        }
    }

    private static Element element(String name, OutputRecord record, Layout layout) {
        Element root = new Element(name);
        for (FieldDefinition field : layout.fields()) {
            Object value = record.values().get(field.name());
            root.put(field.column() != null ? field.column() : field.name(), value == null ? null : Values.canonical(value));
        }
        for (OutputRecord.Children children : record.children()) {
            Element container = root.path(children.layout().nestedKey());
            String childName = children.layout().formatOptions().xml().recordElement() != null
                    ? children.layout().formatOptions().xml().recordElement() : children.layout().name();
            for (OutputRecord child : children.records()) {
                container.children.add(element(childName, child, children.layout()));
            }
        }
        return root;
    }

    @Override
    public void finish() {
        try {
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("cannot finish the XML document: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            xml.close();
            out.close();
        } catch (XMLStreamException | java.io.IOException e) {
            throw new IllegalStateException("cannot close the XML document: " + e.getMessage(), e);
        }
    }

    private static final class Element {
        final String name;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<Element> children = new ArrayList<>();
        String text;

        Element(String name) {
            this.name = name;
        }

        /** Sets {@code path} (dotted, last part {@code @x} for an attribute) relative to this element. */
        void put(String path, String value) {
            int dot = path.lastIndexOf('.');
            Element parent = dot < 0 ? this : path(path.substring(0, dot));
            String last = dot < 0 ? path : path.substring(dot + 1);
            if (last.startsWith("@")) {
                if (value != null) {
                    parent.attributes.put(last.substring(1), value);
                }
            } else {
                Element leaf = parent.child(last);
                leaf.text = value;
            }
        }

        Element path(String path) {
            Element current = this;
            for (String part : path.split("\\.")) {
                current = current.child(part);
            }
            return current;
        }

        private Element child(String name) {
            for (Element child : children) {
                if (child.name.equals(name)) {
                    return child;
                }
            }
            Element child = new Element(name);
            children.add(child);
            return child;
        }

        void write(XMLStreamWriter xml) throws XMLStreamException {
            if (children.isEmpty() && (text == null || text.isEmpty())) {
                xml.writeEmptyElement(name);
                writeAttributes(xml);
                return;
            }
            xml.writeStartElement(name);
            writeAttributes(xml);
            if (text != null) {
                xml.writeCharacters(text);
            }
            for (Element child : children) {
                child.write(xml);
            }
            xml.writeEndElement();
        }

        private void writeAttributes(XMLStreamWriter xml) throws XMLStreamException {
            for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                xml.writeAttribute(attribute.getKey(), attribute.getValue());
            }
        }
    }
}
