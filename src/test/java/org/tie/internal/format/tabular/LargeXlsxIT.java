package org.tie.internal.format.tabular;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.Warning;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.Layout;
import org.tie.internal.engine.ImportEngine;
import org.tie.internal.engine.LineResult;
import org.tie.internal.format.ReadRecord;
import org.tie.internal.format.RecordReader;
import org.tie.internal.format.RecordReaders;

/** Runs in failsafe with {@code -Xmx256m} (tie-core/pom.xml). */
@Tag("slow")
class LargeXlsxIT {

    private static final int ROWS = 200_000;

    @TempDir
    Path dir;

    @Test
    @DisplayName("AC-36 an XLSX of 200,000 rows is read with a 256 MB heap")
    void readsWithBoundedMemory() throws IOException {
        assertThat(Runtime.getRuntime().maxMemory()).as("max heap").isLessThanOrEqualTo(300L * 1024 * 1024);
        Path file = dir.resolve("large.xlsx");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); OutputStream out = Files.newOutputStream(file)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("Data");
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            Row header = sheet.createRow(0);
            String[] names = {"id", "name", "amount", "born", "note"};
            for (int i = 0; i < names.length; i++) {
                header.createCell(i).setCellValue(names[i]);
            }
            for (int r = 1; r <= ROWS; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue(r);
                row.createCell(1).setCellValue("name-" + r);
                row.createCell(2).setCellValue(r * 1.25);
                row.createCell(3).setCellValue(LocalDate.of(2000, 1, 1).plusDays(r % 9000));
                row.getCell(3).setCellStyle(date);
                row.createCell(4).setCellValue("a fairly long note to make every row heavier than a few bytes " + r);
            }
            workbook.write(out);
        }
        Layout layout = Layout.builder("l").allowedFormats(FileFormat.XLSX)
                .field(FieldDefinition.builder("id").type(FieldType.INTEGER))
                .field(FieldDefinition.builder("name"))
                .field(FieldDefinition.builder("amount").type(FieldType.DECIMAL))
                .field(FieldDefinition.builder("born").type(FieldType.DATE))
                .field(FieldDefinition.builder("note"))
                .build();
        ImportEngine engine = new ImportEngine(layout, java.util.Map.of(), null);
        List<Warning> warnings = new ArrayList<>();

        long accepted = 0;
        Object lastId = null;
        try (RecordReader reader = RecordReaders.open(FileFormat.XLSX, file, layout, List.of(layout), warnings::add,
                "large.xlsx")) {
            ReadRecord record;
            while ((record = reader.next()) != null) {
                if (engine.process(record.input()) instanceof LineResult.Accepted(var values)) {
                    accepted++;
                    lastId = values.get("id");
                }
            }
        }

        assertThat(accepted).isEqualTo(ROWS);
        assertThat(lastId).isEqualTo((long) ROWS);
        assertThat(warnings).isEmpty();
    }
}
