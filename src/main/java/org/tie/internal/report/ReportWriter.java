package org.tie.internal.report;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.tie.config.FileFormat;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a result report (§4.1 "Informe de resultado"). CSV/XLSX: the raw values plus
 * {@code LINE_NUMBER}, {@code RESULT}, {@code REASON} and {@code CODES}, with formula injection
 * neutralized (AC-89). JSON: an array of {@code {LINE_NUMBER, RESULT, REASON, values, errors}}. XML:
 * {@code <report><line number result><reason/><values/><errors/></line></report>}.
 */
public final class ReportWriter {

    static final List<String> COLUMNS = List.of("LINE_NUMBER", "RESULT", "REASON", "CODES");

    private ReportWriter() {
    }

    public static void write(FileFormat format, Path target, Set<String> keys, Iterator<ReportLine> lines)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            switch (format) {
                case CSV -> csv(out, keys, lines);
                case XLSX -> xlsx(out, keys, lines);
                case JSON -> json(out, lines);
                case XML -> xml(out, lines);
            }
        }
    }

    /**
     * A value that a spreadsheet would run as a formula ({@code = + - @}, tab or carriage return first)
     * is prefixed with {@code '}.
     */
    static String neutralize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }

    private static List<String> row(Set<String> keys, ReportLine line) {
        List<String> row = new ArrayList<>();
        for (String key : keys) {
            row.add(neutralize(line.values().getOrDefault(key, "")));
        }
        row.add(String.valueOf(line.lineNumber()));
        row.add(line.result());
        row.add(neutralize(line.reason()));
        row.add(line.codes());
        return row;
    }

    private static List<String> header(Set<String> keys) {
        List<String> header = new ArrayList<>();
        keys.forEach(key -> header.add(neutralize(key)));
        header.addAll(COLUMNS);
        return header;
    }

    private static void csv(OutputStream out, Set<String> keys, Iterator<ReportLine> lines) throws IOException {
        CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setRecordSeparator("\r\n").get());
        printer.printRecord(header(keys));
        while (lines.hasNext()) {
            printer.printRecord(row(keys, lines.next()));
        }
        printer.flush();
    }

    private static void xlsx(OutputStream out, Set<String> keys, Iterator<ReportLine> lines) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("Report");
            int rowNumber = 0;
            write(sheet.createRow(rowNumber++), header(keys));
            while (lines.hasNext()) {
                ReportLine line = lines.next();
                Row row = sheet.createRow(rowNumber++);
                write(row, row(keys, line));
                row.getCell(keys.size()).setCellValue(line.lineNumber());
            }
            workbook.write(out);
        }
    }

    private static void write(Row row, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            row.createCell(i).setCellValue(values.get(i));
        }
    }

    private static void json(OutputStream out, Iterator<ReportLine> lines) {
        JsonMapper mapper = JsonMapper.builder().build();
        try (JsonGenerator generator = mapper.createGenerator(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            generator.writeStartArray();
            while (lines.hasNext()) {
                ReportLine line = lines.next();
                generator.writeStartObject();
                generator.writeNumberProperty("LINE_NUMBER", line.lineNumber());
                generator.writeStringProperty("RESULT", line.result());
                generator.writeStringProperty("REASON", line.reason());
                generator.writeName("values");
                mapper.writeValue(generator, line.values());
                generator.writeName("errors");
                mapper.writeValue(generator, line.errors());
                generator.writeEndObject();
            }
            generator.writeEndArray();
        }
    }

    private static void xml(OutputStream out, Iterator<ReportLine> lines) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("report");
            while (lines.hasNext()) {
                ReportLine line = lines.next();
                xml.writeStartElement("line");
                xml.writeAttribute("number", String.valueOf(line.lineNumber()));
                xml.writeAttribute("result", line.result());
                element(xml, "reason", line.reason());
                xml.writeStartElement("values");
                for (Map.Entry<String, String> value : line.values().entrySet()) {
                    xml.writeStartElement("value");
                    xml.writeAttribute("name", value.getKey());
                    xml.writeCharacters(value.getValue());
                    xml.writeEndElement();
                }
                xml.writeEndElement();
                xml.writeStartElement("errors");
                for (ReportLine.Error error : line.errors()) {
                    xml.writeStartElement("error");
                    xml.writeAttribute("code", error.code());
                    if (error.field() != null) {
                        xml.writeAttribute("field", error.field());
                    }
                    element(xml, "message", error.message());
                    for (Map.Entry<String, String> arg : error.args().entrySet()) {
                        xml.writeStartElement("arg");
                        xml.writeAttribute("name", arg.getKey());
                        xml.writeCharacters(arg.getValue());
                        xml.writeEndElement();
                    }
                    xml.writeEndElement();
                }
                xml.writeEndElement();
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            writer.flush();
        } catch (XMLStreamException e) {
            throw new IOException("cannot write the XML report: " + e.getMessage(), e);
        }
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(text);
        xml.writeEndElement();
    }
}
