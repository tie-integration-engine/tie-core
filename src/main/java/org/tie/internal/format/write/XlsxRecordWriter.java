package org.tie.internal.format.write;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.tie.config.Layout;
import org.tie.config.XlsxOptions;

/**
 * XLSX export with a streaming workbook: text values as text cells, NATIVE values as numeric, boolean or
 * date cells (§4.1 "Forma de la salida").
 */
final class XlsxRecordWriter implements RecordWriter {

    private final OutputStream out;
    private final SXSSFWorkbook workbook = new SXSSFWorkbook(100);
    private final Sheet sheet;
    private final Columns columns;
    private final CellStyle dateStyle;
    private final CellStyle dateTimeStyle;
    private int nextRow;

    XlsxRecordWriter(OutputStream out, Layout layout) {
        this.out = out;
        XlsxOptions options = layout.formatOptions().xlsx();
        workbook.setCompressTempFiles(true);
        this.sheet = workbook.createSheet(options.sheet() == null ? "Sheet1" : options.sheet());
        this.columns = Columns.of(layout);
        this.dateStyle = style("yyyy-mm-dd");
        this.dateTimeStyle = style("yyyy-mm-dd hh:mm:ss");
        if (options.hasHeader()) {
            Row header = sheet.createRow(nextRow++);
            List<String> names = columns.header();
            for (int i = 0; i < names.size(); i++) {
                header.createCell(i).setCellValue(names.get(i));
            }
        }
    }

    private CellStyle style(String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    @Override
    public void write(OutputRecord record) {
        Row row = sheet.createRow(nextRow++);
        List<String> fields = columns.fields();
        for (int i = 0; i < fields.size(); i++) {
            Object value = fields.get(i) == null ? null : record.values().get(fields.get(i));
            if (value != null) {
                cell(row.createCell(i), value);
            }
        }
    }

    private void cell(Cell cell, Object value) {
        switch (value) {
            case String text -> cell.setCellValue(text);
            case Long number -> cell.setCellValue(number);
            case BigDecimal number -> cell.setCellValue(number.doubleValue());
            case Boolean bool -> cell.setCellValue(bool);
            case LocalDate date -> {
                cell.setCellValue(date);
                cell.setCellStyle(dateStyle);
            }
            case LocalDateTime dateTime -> {
                cell.setCellValue(dateTime);
                cell.setCellStyle(dateTimeStyle);
            }
            default -> cell.setCellValue(value.toString());
        }
    }

    @Override
    public void finish() {
        try {
            workbook.write(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try (out) {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
