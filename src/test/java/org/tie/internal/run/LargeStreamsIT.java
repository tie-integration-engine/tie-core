package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.FileStatus;
import org.tie.ImportContext;
import org.tie.ImportResult;
import org.tie.Tie;
import org.tie.config.CsvOptions;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.config.ProvidedStreamChannel;
import org.tie.spi.Outcome;

/**
 * INV-13: files far larger than the heap are imported in streaming. Runs in failsafe with
 * {@code -Xmx256m}; the inputs are generated on the fly, so nothing touches the disk.
 */
@Tag("slow")
class LargeStreamsIT {

    private static final long SIZE = 1L << 30;

    @TempDir
    Path work;

    /** A stream of {@code size} bytes: the header, then {@code row} repeated, then {@code footer}. */
    private static InputStream generated(String header, String row, String separator, String footer, long size) {
        byte[] head = header.getBytes(StandardCharsets.UTF_8);
        byte[] line = (row + separator).getBytes(StandardCharsets.UTF_8);
        byte[] last = (row + footer).getBytes(StandardCharsets.UTF_8);
        long rows = Math.max(1, (size - head.length - last.length) / line.length);
        return new InputStream() {
            private long row = -1;
            private byte[] current = head;
            private int position;

            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                int written = 0;
                while (written < length) {
                    if (position == current.length) {
                        row++;
                        if (row > rows) {
                            return written == 0 ? -1 : written;
                        }
                        current = row == rows ? last : line;
                        position = 0;
                    }
                    int count = Math.min(length - written, current.length - position);
                    System.arraycopy(current, position, buffer, offset + written, count);
                    position += count;
                    written += count;
                }
                return written;
            }
        };
    }

    private ImportResult importStream(Layout.Builder layout, String fileName, InputStream stream, AtomicLong count) {
        IntegrationDefinition definition = IntegrationDefinition.importing("big").inputChannel(ProvidedStreamChannel.INSTANCE)
                .layout(layout).build();
        return Tie.builder().build().importFiles(definition, ImportContext.builder(record -> {
            count.incrementAndGet();
            return Outcome.APPLIED;
        }).providedInput(layout.build().name(), fileName, stream).workingDirectory(work).build());
    }

    @Test
    void aOneGigabyteCsvWithA256MegabyteHeap() {
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(300L * 1024 * 1024);
        AtomicLong count = new AtomicLong();
        Layout.Builder layout = Layout.builder("rows").fileNamePattern("rows").allowedFormats(FileFormat.CSV)
                .csv(CsvOptions.builder().separator(';').build())
                .field(FieldDefinition.builder("id").type(FieldType.INTEGER))
                .field(FieldDefinition.builder("name"))
                .field(FieldDefinition.builder("amount").type(FieldType.DECIMAL));

        ImportResult result = importStream(layout, "rows.csv",
                generated("id;name;amount\n", "123456;a fairly ordinary name for a row;1234.56", "\n", "\n", SIZE), count);

        assertThat(result.files()).singleElement().satisfies(file -> {
            assertThat(file.status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(file.applied()).isEqualTo(count.get());
        });
        assertThat(count.get()).isGreaterThan(20_000_000L);
    }

    @Test
    void aOneGigabyteJsonWithA256MegabyteHeap() {
        AtomicLong count = new AtomicLong();
        Layout.Builder layout = Layout.builder("rows").fileNamePattern("rows").allowedFormats(FileFormat.JSON)
                .json(new JsonOptions("rows", null))
                .field(FieldDefinition.builder("id").type(FieldType.INTEGER))
                .field(FieldDefinition.builder("name").column("person.name"));

        ImportResult result = importStream(layout, "rows.json", generated("{\"rows\":[",
                "{\"id\":123456,\"person\":{\"name\":\"a fairly ordinary name\"}}", ",", "]}", SIZE), count);

        assertThat(result.files()).singleElement()
                .satisfies(file -> assertThat(file.status()).isEqualTo(FileStatus.SUCCESS));
        assertThat(count.get()).isGreaterThan(10_000_000L);
        assertThat(result.warnings()).isEmpty();
    }
}
