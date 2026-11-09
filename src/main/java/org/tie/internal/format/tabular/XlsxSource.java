package org.tie.internal.format.tabular;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.tie.FileErrorType;
import org.tie.config.XlsxCellValues;
import org.tie.config.XlsxOptions;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.SecureXml;

/**
 * Rows of one XLSX sheet, streamed with StAX over the sheet XML (never the whole workbook in memory,
 * INV-13). With {@code cellValues CANONICAL} (the default) values are the stored ones, normalized (§4.1
 * "Celdas XLSX"): canonical numbers, ISO dates, {@code true}/{@code false}, and the cached value of formulas.
 * With {@code FORMATTED} numbers and dates are the text Excel displays, per the cell's number format and the
 * options' locale (POI {@link DataFormatter}), and booleans are {@code TRUE}/{@code FALSE}. POI's zip-bomb
 * limits apply (AC-38).
 */
public final class XlsxSource implements TabularSource {

    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final OPCPackage pkg;
    private final InputStream sheet;
    private final XMLStreamReader xml;
    private final ReadOnlySharedStringsTable sharedStrings;
    private final StylesTable styles;
    private final boolean date1904;
    /** Null with CANONICAL values. */
    private final DataFormatter formatter;
    private int lastRow;

    public XlsxSource(Path file, XlsxOptions options) {
        OPCPackage opened = null;
        InputStream sheetStream = null;
        try {
            opened = OPCPackage.open(file.toFile(), PackageAccess.READ);
            XSSFReader reader = new XSSFReader(opened);
            this.sharedStrings = new ReadOnlySharedStringsTable(opened, false);
            this.styles = reader.getStylesTable();
            this.date1904 = isDate1904(reader);
            this.formatter = options.effectiveCellValues() == XlsxCellValues.FORMATTED
                    ? new DataFormatter(options.effectiveLocale()) : null;
            sheetStream = selectSheet(reader, options.sheet());
            this.xml = SecureXml.inputFactory().createXMLStreamReader(sheetStream);
            this.pkg = opened;
            this.sheet = sheetStream;
        } catch (FileFailure e) {
            closeQuietly(sheetStream, opened);
            throw e;
        } catch (Exception e) {
            closeQuietly(sheetStream, opened);
            throw failure(e, null);
        }
    }

    @Override
    public TabularRow nextRow() {
        try {
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("row")) {
                    return row();
                }
            }
            return null;
        } catch (FileFailure e) {
            throw e;
        } catch (Exception e) {
            throw failure(e, lastRow + 1);
        }
    }

    private TabularRow row() throws XMLStreamException {
        String r = xml.getAttributeValue(null, "r");
        int rowNumber = r == null ? lastRow + 1 : Integer.parseInt(r);
        lastRow = rowNumber;
        List<String> cells = new ArrayList<>();
        int nextColumn = 0;
        boolean anyValue = false;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("row")) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("c")) {
                String ref = xml.getAttributeValue(null, "r");
                int column = ref == null ? nextColumn : columnIndex(ref);
                String value = cell(xml.getAttributeValue(null, "t"), xml.getAttributeValue(null, "s"));
                while (cells.size() < column) {
                    cells.add("");
                }
                cells.add(value);
                nextColumn = column + 1;
                anyValue |= !value.isEmpty();
            }
        }
        return new TabularRow(rowNumber, anyValue ? cells : List.of(), !anyValue);
    }

    /** Reads one {@code <c>} element up to its end and returns its normalized value. */
    private String cell(String type, String style) throws XMLStreamException {
        StringBuilder stored = null;
        StringBuilder inline = null;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("c")) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (xml.getLocalName()) {
                    case "v" -> stored = new StringBuilder(xml.getElementText());
                    case "t" -> (inline == null ? inline = new StringBuilder() : inline).append(xml.getElementText());
                    default -> {
                    }
                }
            }
        }
        if ("inlineStr".equals(type)) {
            return inline == null ? "" : inline.toString();
        }
        if (stored == null) {
            return "";
        }
        String value = stored.toString();
        return switch (type == null ? "n" : type) {
            case "s" -> sharedStrings.getItemAt(Integer.parseInt(value.strip())).getString();
            case "b" -> {
                boolean truth = "1".equals(value.strip());
                yield formatter != null ? (truth ? "TRUE" : "FALSE") : (truth ? "true" : "false");
            }
            case "n" -> number(value.strip(), style);
            default -> value;
        };
    }

    private String number(String value, String style) {
        if (value.isEmpty()) {
            return "";
        }
        if (formatter != null) {
            return formatted(Double.parseDouble(value), style);
        }
        if (style != null && isDateStyle(Integer.parseInt(style))) {
            double serial = Double.parseDouble(value);
            if (DateUtil.isValidExcelDate(serial)) {
                LocalDateTime dateTime = DateUtil.getLocalDateTime(serial, date1904, true);
                return dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)
                        ? dateTime.toLocalDate().toString()
                        : ISO_DATE_TIME.format(dateTime);
            }
        }
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    /** The text Excel displays for a number with the cell's style; no style or an unknown one is General. */
    private String formatted(double number, String style) {
        int formatIndex = 0;
        String format = BuiltinFormats.getBuiltinFormat(0);
        if (style != null && styles != null) {
            int styleIndex = Integer.parseInt(style);
            if (styleIndex < styles.getNumCellStyles()) {
                XSSFCellStyle cellStyle = styles.getStyleAt(styleIndex);
                if (cellStyle != null) {
                    formatIndex = cellStyle.getDataFormat();
                    String styleFormat = cellStyle.getDataFormatString();
                    format = styleFormat != null ? styleFormat : BuiltinFormats.getBuiltinFormat(formatIndex);
                    if (format == null) {
                        format = BuiltinFormats.getBuiltinFormat(0);
                    }
                }
            }
        }
        return formatter.formatRawCellContents(number, formatIndex, format, date1904);
    }

    private boolean isDateStyle(int styleIndex) {
        if (styles == null || styleIndex >= styles.getNumCellStyles()) {
            return false;
        }
        XSSFCellStyle cellStyle = styles.getStyleAt(styleIndex);
        return cellStyle != null && DateUtil.isADateFormat(cellStyle.getDataFormat(), cellStyle.getDataFormatString());
    }

    /** Zero-based column of a cell reference such as {@code AB12}. */
    static int columnIndex(String reference) {
        int column = 0;
        for (int i = 0; i < reference.length() && Character.isLetter(reference.charAt(i)); i++) {
            column = column * 26 + (Character.toUpperCase(reference.charAt(i)) - 'A' + 1);
        }
        return column - 1;
    }

    private static InputStream selectSheet(XSSFReader reader, String name) throws Exception {
        Iterator<InputStream> sheets = reader.getSheetsData();
        XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) sheets;
        List<String> names = new ArrayList<>();
        while (iterator.hasNext()) {
            InputStream stream = iterator.next();
            if (name == null || iterator.getSheetName().equals(name)) {
                return stream;
            }
            names.add(iterator.getSheetName());
            stream.close();
        }
        throw FileFailure.structural(name == null ? "the workbook has no sheets"
                : "sheet '" + name + "' not found (sheets: " + String.join(", ", names) + ")", null);
    }

    private static boolean isDate1904(XSSFReader reader) throws Exception {
        try (InputStream workbook = reader.getWorkbookData()) {
            XMLStreamReader xml = SecureXml.inputFactory().createXMLStreamReader(workbook);
            try {
                while (xml.hasNext()) {
                    if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("workbookPr")) {
                        String value = xml.getAttributeValue(null, "date1904");
                        return value != null && (value.equals("1") || value.toLowerCase(Locale.ROOT).equals("true"));
                    }
                }
                return false;
            } finally {
                xml.close();
            }
        }
    }

    private static FileFailure failure(Throwable e, Integer row) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("zip bomb")) {
                return new FileFailure(FileErrorType.UNSAFE_CONTENT,
                        "the XLSX file exceeds the compression limits (possible zip bomb)", row, e);
            }
        }
        String where = row == null ? "" : " at row " + row;
        return new FileFailure(FileErrorType.STRUCTURAL, "invalid XLSX file" + where + ": " + e.getMessage(), row, e);
    }

    @Override
    public boolean canonicalValues() {
        return formatter == null;
    }

    @Override
    public void close() {
        try {
            xml.close();
        } catch (XMLStreamException ignored) {
            // closing only
        }
        closeQuietly(sheet, pkg);
    }

    private static void closeQuietly(InputStream stream, OPCPackage pkg) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
            // closing only
        }
        if (pkg != null) {
            pkg.revert();
        }
    }
}
