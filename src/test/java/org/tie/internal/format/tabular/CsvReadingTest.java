package org.tie.internal.format.tabular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.tie.FileErrorType;
import org.tie.WarningCode;
import org.tie.config.ColumnLocation;
import org.tie.config.CsvOptions;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.Layout;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.Reading;

class CsvReadingTest {

    private static Layout layout(ColumnLocation location, CsvOptions options, String... fields) {
        Layout.Builder layout = Layout.builder("l").allowedFormats(FileFormat.CSV).columnLocation(location)
                .csv(options);
        Arrays.stream(fields).forEach(name -> layout.field(FieldDefinition.builder(name)));
        return layout.build();
    }

    private static CsvOptions semicolon() {
        return CsvOptions.builder().separator(';').build();
    }

    private static Reading csv(String content, Layout layout) {
        return Reading.text(FileFormat.CSV, content, layout);
    }

    private static FileFailure failure(String content, Layout layout) {
        try {
            csv(content, layout);
        } catch (FileFailure e) {
            return e;
        }
        throw new AssertionError("expected a FileFailure");
    }

    @Nested
    class Position {

        @Test
        @DisplayName("AC-24 a header that does not match the field is a warning; the position wins")
        void headerMismatch() {
            Reading reading = csv("x;b\n1;2\n", layout(ColumnLocation.POSITION, semicolon(), "a", "b"));

            assertThat(reading.inputs()).containsExactly(Map.of("a", "1", "b", "2"));
            assertThat(reading.warnings).singleElement().satisfies(warning -> {
                assertThat(warning.code()).isEqualTo(WarningCode.HEADER_MISMATCH);
                assertThat(warning.message()).startsWith("header mismatch at column 1");
                assertThat(warning.fileName()).isEqualTo("test.csv");
            });
        }

        @Test
        @DisplayName("AC-25 extra columns are a warning and are ignored")
        void extraColumns() {
            Reading reading = csv("a;b;c\n1;2;3\n4;5;6\n", layout(ColumnLocation.POSITION, semicolon(), "a", "b"));

            assertThat(reading.inputs()).containsExactly(Map.of("a", "1", "b", "2"), Map.of("a", "4", "b", "5"));
            assertThat(reading.warnings).extracting(w -> w.code()).containsExactly(WarningCode.EXTRA_COLUMNS);
            assertThat(reading.raw().getFirst()).containsExactly(entry("a", "1"), entry("b", "2"), entry("c", "3"));
        }

        @Test
        @DisplayName("AC-30 a short line leaves the missing fields empty")
        void shortLine() {
            Reading reading = csv("a;b\n1\n", layout(ColumnLocation.POSITION, semicolon(), "a", "b"));

            assertThat(reading.inputs().getFirst()).containsEntry("a", "1").containsEntry("b", null);
            assertThat(reading.typed().getFirst()).containsEntry("b", null);
        }

        @Test
        @DisplayName("AC-65 a field with inInput = false consumes no column and takes its default")
        void notInInput() {
            Layout layout = Layout.builder("l").allowedFormats(FileFormat.CSV).columnLocation(ColumnLocation.POSITION)
                    .csv(semicolon())
                    .field(FieldDefinition.builder("a"))
                    .field(FieldDefinition.builder("one").inInput(false).defaultValue("1"))
                    .field(FieldDefinition.builder("b"))
                    .build();

            Reading reading = csv("a;b\nx;y\n", layout);

            assertThat(reading.typed()).containsExactly(Map.of("a", "x", "one", "1", "b", "y"));
            assertThat(reading.warnings).isEmpty();
        }

        @Test
        void withoutHeaderRawKeysArePositions() {
            CsvOptions noHeader = CsvOptions.builder().separator(';').hasHeader(false).firstDataRow(1).build();

            Reading reading = csv("1;2\n", layout(ColumnLocation.POSITION, noHeader, "a", "b"));

            assertThat(reading.raw()).containsExactly(Map.of("column1", "1", "column2", "2"));
            assertThat(reading.rows()).containsExactly(1);
        }
    }

    @Nested
    class Header {

        @Test
        @DisplayName("AC-26 a header column that is not configured is a structural error")
        void unknownHeaderColumn() {
            FileFailure failure = failure("a;zz\n1;2\n", layout(ColumnLocation.HEADER, semicolon(), "a"));

            assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(failure).hasMessage("header columns not configured in layout 'l': zz");
            assertThat(failure.rowNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-27 header names match ignoring case and surrounding spaces")
        void headerMatchesLoosely() {
            Reading reading = csv(" NIF ;Sexo\n1;M\n", layout(ColumnLocation.HEADER, semicolon(), "nif", "sexo"));

            assertThat(reading.inputs()).containsExactly(Map.of("nif", "1", "sexo", "M"));
            assertThat(reading.raw()).containsExactly(Map.of("NIF", "1", "Sexo", "M"));
        }

        @Test
        @DisplayName("AC-162 a field without a column in the header is a structural error naming it")
        void missingColumn() {
            FileFailure failure = failure("nif\n1\n", layout(ColumnLocation.HEADER, semicolon(), "nif", "email"));

            assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(failure).hasMessageContaining("email");
        }

        @Test
        @DisplayName("AC-163 an optional field without column is empty and onEmpty resolves it")
        void optionalMissingColumn() {
            Layout layout = Layout.builder("l").allowedFormats(FileFormat.CSV).csv(semicolon())
                    .field(FieldDefinition.builder("nif"))
                    .field(FieldDefinition.builder("email").optional(true).defaultValue("none"))
                    .build();

            assertThat(csv("nif\n1\n", layout).typed()).containsExactly(Map.of("nif", "1", "email", "none"));
        }

        @Test
        @DisplayName("AC-164 a field with inInput = false does not need a header column")
        void notInInputNotInHeader() {
            Layout layout = Layout.builder("l").allowedFormats(FileFormat.CSV).csv(semicolon())
                    .field(FieldDefinition.builder("nif"))
                    .field(FieldDefinition.builder("country").inInput(false).defaultValue("ES"))
                    .build();

            assertThat(csv("nif\n1\n", layout).typed()).containsExactly(Map.of("nif", "1", "country", "ES"));
        }

        @Test
        void columnNamesTheHeaderAndDuplicatesAreStructural() {
            Layout layout = Layout.builder("l").allowedFormats(FileFormat.CSV).csv(semicolon())
                    .field(FieldDefinition.builder("taxId").column("NIF")).build();

            assertThat(csv("nif\n1\n", layout).inputs()).containsExactly(Map.of("taxId", "1"));
            assertThat(failure("nif;NIF\n1;2\n", layout))
                    .hasMessage("duplicate header 'NIF' at columns 1 and 2");
        }

        @Test
        void emptyHeaderCellsAreIgnoredWithAWarning() {
            Reading reading = csv("nif;\n1;x\n", layout(ColumnLocation.HEADER, semicolon(), "nif"));

            assertThat(reading.inputs()).containsExactly(Map.of("nif", "1"));
            assertThat(reading.raw()).containsExactly(Map.of("nif", "1", "column2", "x"));
            assertThat(reading.warnings).singleElement().satisfies(w -> {
                assertThat(w.code()).isEqualTo(WarningCode.EXTRA_COLUMNS);
                assertThat(w.message()).isEqualTo("column 2 has no header and is ignored");
            });
        }
    }

    @Nested
    class Letter {

        @Test
        @DisplayName("AC-28 letters beyond the file's columns leave the fields empty, with a warning")
        void letterBeyondTheFile() {
            Layout layout = Layout.builder("l").allowedFormats(FileFormat.CSV).columnLocation(ColumnLocation.LETTER)
                    .csv(semicolon())
                    .field(FieldDefinition.builder("a").column("C"))
                    .field(FieldDefinition.builder("b").column("C"))
                    .build();

            Reading reading = csv("x;y\n1;2\n", layout);

            assertThat(reading.typed()).singleElement()
                    .satisfies(values -> assertThat(values).containsEntry("a", null).containsEntry("b", null));
            assertThat(reading.warnings).singleElement().satisfies(w -> {
                assertThat(w.code()).isEqualTo(WarningCode.MISSING_COLUMN);
                assertThat(w.message()).contains("a, b");
            });
        }

        @Test
        @DisplayName("AC-29 two fields on the same letter each get the value")
        void sameLetterTwice() {
            Layout layout = Layout.builder("l").allowedFormats(FileFormat.CSV).columnLocation(ColumnLocation.LETTER)
                    .csv(CsvOptions.builder().separator(';').hasHeader(false).firstDataRow(1).build())
                    .field(FieldDefinition.builder("a").column("C"))
                    .field(FieldDefinition.builder("b").column("C").textTransform(org.tie.config.TextTransform.padLeft(3, '0')))
                    .build();

            Reading reading = csv("x;y;7\n", layout);

            assertThat(reading.typed()).containsExactly(Map.of("a", "7", "b", "007"));
            assertThat(reading.raw()).containsExactly(Map.of("A", "x", "B", "y", "C", "7"));
        }

        @Test
        void lettersBeyondZ() {
            assertThat(ColumnMapping.letterIndex("A")).isZero();
            assertThat(ColumnMapping.letterIndex("Z")).isEqualTo(25);
            assertThat(ColumnMapping.letterIndex("AA")).isEqualTo(26);
            assertThat(ColumnMapping.letterIndex("ZZZ")).isEqualTo(18277);
            assertThat(ColumnMapping.letter(18277)).isEqualTo("ZZZ");
            assertThat(ColumnMapping.letter(26)).isEqualTo("AA");
        }
    }

    @Nested
    class RowsAndEncoding {

        @Test
        @DisplayName("AC-31 a completely empty line is skipped and does not count as a record")
        void emptyLineIsSkipped() {
            Reading reading = csv("a\n1\n2\n\n3\n", layout(ColumnLocation.HEADER, semicolon(), "a"));

            assertThat(reading.inputs()).extracting(input -> input.get("a")).containsExactly("1", "2", "3");
            assertThat(reading.rows()).containsExactly(2, 3, 5);
        }

        @Test
        @DisplayName("AC-32 a line of separators is a record with every field empty")
        void separatorsOnly() {
            Reading reading = csv("a;b;c\n;;\n", layout(ColumnLocation.HEADER, semicolon(), "a", "b", "c"));

            assertThat(reading.inputs()).containsExactly(Map.of("a", "", "b", "", "c", ""));
        }

        @Test
        @DisplayName("AC-33 the UTF-8 BOM is not part of the first header name")
        void bom() {
            byte[] content = "﻿nif;sexo\n1;M\n".getBytes(StandardCharsets.UTF_8);

            Reading reading = Reading.bytes(FileFormat.CSV, content, layout(ColumnLocation.HEADER, semicolon(), "nif", "sexo"));

            assertThat(reading.raw()).containsExactly(Map.of("nif", "1", "sexo", "M"));
        }

        @Test
        @DisplayName("AC-34 an invalid UTF-8 byte fails the file with ENCODING at its physical row")
        void invalidByte() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.writeBytes("a\n".getBytes(StandardCharsets.UTF_8));
            for (int row = 2; row < 10; row++) {
                bytes.writeBytes(("v" + row + "\n").getBytes(StandardCharsets.UTF_8));
            }
            bytes.writeBytes(new byte[]{'x', (byte) 0xFF, '\n', 'y', '\n'});
            java.util.List<String> seen = new java.util.ArrayList<>();
            Layout layout = layout(ColumnLocation.HEADER, semicolon(), "a");

            try (var reader = org.tie.internal.format.RecordReaders.open(FileFormat.CSV,
                    new java.io.ByteArrayInputStream(bytes.toByteArray()), layout, java.util.List.of(layout), w -> {
                    }, "f.csv")) {
                assertThatThrownBy(() -> {
                    org.tie.internal.format.ReadRecord record;
                    while ((record = reader.next()) != null) {
                        seen.add(record.input().fieldInputs().get("a"));
                    }
                }).isInstanceOfSatisfying(FileFailure.class, failure -> {
                    assertThat(failure.type()).isEqualTo(FileErrorType.ENCODING);
                    assertThat(failure.rowNumber()).isEqualTo(10);
                });
            }
            assertThat(seen).containsExactly("v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9");
        }

        @Test
        void unclosedQuoteIsStructural() {
            FileFailure failure = failure("a\n1\n\"2\n3\n", layout(ColumnLocation.HEADER, semicolon(), "a"));

            assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(failure.rowNumber()).isEqualTo(3);
        }

        @Test
        void quotedLineBreaksStayInOneRow() {
            Reading reading = csv("a;b\n\"x\ny\";2\n3;4\n", layout(ColumnLocation.HEADER, semicolon(), "a", "b"));

            assertThat(reading.inputs()).containsExactly(Map.of("a", "x\ny", "b", "2"), Map.of("a", "3", "b", "4"));
            assertThat(reading.rows()).containsExactly(2, 3);
        }

        @Test
        void otherEncodingsAndValuesAreNotTrimmed() {
            CsvOptions latin1 = CsvOptions.builder().separator(';').encoding(StandardCharsets.ISO_8859_1).build();

            Reading reading = Reading.text(FileFormat.CSV, "nombre\n José \n", StandardCharsets.ISO_8859_1,
                    layout(ColumnLocation.HEADER, latin1, "nombre"));

            assertThat(reading.inputs()).containsExactly(Map.of("nombre", " José "));
        }
    }

    @Nested
    class BlankRecordsAndPreamble {

        private final CsvOptions skipBlank = CsvOptions.builder().separator(';').skipBlankRecords(true).build();

        @Test
        @DisplayName("AC-138 with skipBlankRecords a row of separators is not a record")
        void separatorsAreSkipped() {
            Reading reading = csv("a;b;c;d\n1;;;\n2;;;\n;;;\n3;;;\n", layout(ColumnLocation.HEADER, skipBlank, "a", "b", "c", "d"));

            assertThat(reading.inputs()).extracting(input -> input.get("a")).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("AC-139 with skipBlankRecords a row of blanks is skipped")
        void blanksAreSkipped() {
            assertThat(csv("a;b;c\n\" \";;\n", layout(ColumnLocation.HEADER, skipBlank, "a", "b", "c")).records).isEmpty();
        }

        @Test
        @DisplayName("AC-140 with skipBlankRecords a row with one value is a record")
        void oneValueIsARecord() {
            assertThat(csv("a;b;c\n;x;\n", layout(ColumnLocation.HEADER, skipBlank, "a", "b", "c")).inputs())
                    .containsExactly(Map.of("a", "", "b", "x", "c", ""));
        }

        @Test
        @DisplayName("AC-141 by default a row of separators is a record of empty fields")
        void defaultKeepsSeparatorRows() {
            assertThat(csv("a;b\n;;\n", layout(ColumnLocation.POSITION, semicolon(), "a", "b")).records).hasSize(1);
        }

        @Test
        @DisplayName("AC-142 a header after two preamble rows")
        void headerAfterPreamble() {
            CsvOptions options = CsvOptions.builder().separator(';').firstDataRow(4).build();

            Reading reading = csv("Report;2026\nGenerated by X\nnif;sexo\n1;M\n2;F\n",
                    layout(ColumnLocation.HEADER, options, "nif", "sexo"));

            assertThat(reading.inputs()).containsExactly(Map.of("nif", "1", "sexo", "M"), Map.of("nif", "2", "sexo", "F"));
            assertThat(reading.rows()).containsExactly(4, 5);
        }

        @Test
        @DisplayName("AC-143 empty preamble rows count")
        void emptyPreambleRowsCount() {
            CsvOptions options = CsvOptions.builder().separator(';').firstDataRow(4).build();

            Reading reading = csv("Report\n\nnif;sexo\n1;M\n", layout(ColumnLocation.HEADER, options, "nif", "sexo"));

            assertThat(reading.inputs()).containsExactly(Map.of("nif", "1", "sexo", "M"));
            assertThat(reading.rows()).containsExactly(4);
        }

        @Test
        @DisplayName("AC-146 without header, rows before firstDataRow are ignored")
        void noHeaderFirstDataRow() {
            CsvOptions options = CsvOptions.builder().separator(';').hasHeader(false).firstDataRow(3).build();

            Reading reading = csv("junk\nmore junk;x\n1;2\n", layout(ColumnLocation.POSITION, options, "a", "b"));

            assertThat(reading.inputs()).containsExactly(Map.of("a", "1", "b", "2"));
            assertThat(reading.rows()).containsExactly(3);
        }

        @Test
        void preambleWithAnUnclosedQuoteIsOneRow() {
            CsvOptions options = CsvOptions.builder().separator(';').firstDataRow(3).build();

            Reading reading = csv("\"Report\nline two\"\nnif\n1\n", layout(ColumnLocation.HEADER, options, "nif"));

            assertThat(reading.inputs()).containsExactly(Map.of("nif", "1"));
        }
    }

    @Test
    @DisplayName("AC-40 a layout without fields passes values through as STRING, with or without header")
    void passThrough() {
        Layout withHeader = Layout.builder("l").allowedFormats(FileFormat.CSV).build();
        Layout withoutHeader = Layout.builder("l").allowedFormats(FileFormat.CSV)
                .csv(CsvOptions.builder().hasHeader(false).firstDataRow(1).build()).build();

        assertThat(csv("x,y\n1,\n", withHeader).typed()).singleElement()
                .satisfies(values -> assertThat(values).containsExactly(entry("x", "1"), entry("y", null)));
        assertThat(csv("1,2\n", withoutHeader).typed())
                .containsExactly(Map.of("column1", "1", "column2", "2"));
        assertThat(csv("x,y\n1,2,3\n", withHeader).warnings).isEmpty();
    }

    @Test
    void emptyFileHasNoRecords() {
        assertThat(csv("", layout(ColumnLocation.HEADER, semicolon(), "a")).records).isEmpty();
        assertThat(csv("a\n", layout(ColumnLocation.HEADER, semicolon(), "a")).records).isEmpty();
    }
}
