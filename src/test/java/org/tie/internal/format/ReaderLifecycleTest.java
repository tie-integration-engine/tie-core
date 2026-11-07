package org.tie.internal.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tie.FileErrorType;
import org.tie.config.FileFormat;
import org.tie.config.Layout;
import org.tie.config.XmlOptions;

class ReaderLifecycleTest {

    private static final Layout LAYOUT = Layout.builder("r").allowedFormats(FileFormat.CSV, FileFormat.JSON, FileFormat.XML)
            .xml(XmlOptions.of("rec")).build();

    private static final class TrackingStream extends FilterInputStream {
        boolean closed;

        TrackingStream(String content) {
            super(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "CSV  | a\\n1\\n2\\n3\\n",
            "JSON | [{\"a\":1},{\"a\":2},{\"a\":3}]",
            "XML  | <records><rec><a>1</a></rec><rec><a>2</a></rec><rec><a>3</a></rec></records>",
    })
    void closingBeforeTheEndClosesTheStream(FileFormat format, String content) {
        TrackingStream in = new TrackingStream(content.replace("\\n", "\n"));
        RecordReader reader = RecordReaders.open(format, in, LAYOUT, List.of(LAYOUT), w -> {
        }, "f");

        assertThat(reader.next().input().rawValues()).containsEntry("a", "1");
        reader.close();

        assertThat(in.closed).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "CSV  | a\\n1\\n",
            "JSON | [{\"a\":1}]",
            "XML  | <rec><a>1</a></rec>",
    })
    void aSingleRecordAndThenTheEndAgainAndAgain(FileFormat format, String content) {
        try (RecordReader reader = RecordReaders.open(format, new TrackingStream(content.replace("\\n", "\n")),
                LAYOUT, List.of(LAYOUT), w -> {
                }, "f")) {
            assertThat(reader.next()).isNotNull();
            assertThat(reader.next()).isNull();
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void emptyFiles() {
        assertThat(Reading.text(FileFormat.CSV, "", LAYOUT).records).isEmpty();
        assertThat(Reading.text(FileFormat.JSON, "[]", LAYOUT).records).isEmpty();
        assertThat(Reading.text(FileFormat.XML, "<records/>", LAYOUT).records).isEmpty();
        assertThatThrownBy(() -> Reading.text(FileFormat.JSON, "", LAYOUT))
                .isInstanceOfSatisfying(FileFailure.class, f -> assertThat(f.type()).isEqualTo(FileErrorType.STRUCTURAL));
        assertThatThrownBy(() -> Reading.text(FileFormat.XML, "", LAYOUT))
                .isInstanceOfSatisfying(FileFailure.class, f -> assertThat(f.type()).isEqualTo(FileErrorType.STRUCTURAL));
    }

    @Test
    void xlsxClosedBeforeTheEnd(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("f.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet();
            for (int r = 0; r < 5; r++) {
                sheet.createRow(r).createCell(0).setCellValue(r == 0 ? "a" : "v" + r);
            }
            workbook.write(out);
        }
        Layout xlsx = Layout.builder("r").allowedFormats(FileFormat.XLSX).build();

        RecordReader reader = RecordReaders.open(FileFormat.XLSX, file, xlsx, List.of(xlsx), w -> {
        }, "f.xlsx");
        assertThat(reader.next().input().rawValues()).containsEntry("a", "v1");
        reader.close();

        Files.delete(file);
        assertThat(file).doesNotExist();
    }
}
