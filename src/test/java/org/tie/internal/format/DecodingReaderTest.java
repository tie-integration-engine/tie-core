package org.tie.internal.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecodingReaderTest {

    private static final byte INVALID = (byte) 0xFF;

    private static String readAll(Reader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[7];
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) >= 0) {
            text.append(buffer, 0, read);
        }
        return text.toString();
    }

    private static byte[] withInvalidByteAtLine(int line, int lineLength) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 1; i < line; i++) {
            bytes.writeBytes(("ñ".repeat(lineLength) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        bytes.writeBytes("abc".getBytes(StandardCharsets.UTF_8));
        bytes.write(INVALID);
        bytes.writeBytes("def\n".getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    @Test
    void stripsTheUtf8ByteOrderMark() throws IOException {
        byte[] bytes = "﻿nif;sexo\n".getBytes(StandardCharsets.UTF_8);

        assertThat(readAll(new DecodingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)))
                .isEqualTo("nif;sexo\n");
    }

    @Test
    void decodesUtf16WithItsByteOrderMark() throws IOException {
        byte[] bytes = "año\n".getBytes(StandardCharsets.UTF_16);

        assertThat(readAll(new DecodingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_16)))
                .isEqualTo("año\n");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 5000})
    void reportsTheLineOfTheInvalidBytesAfterReturningEverythingBefore(int line) {
        DecodingReader reader = new DecodingReader(new ByteArrayInputStream(withInvalidByteAtLine(line, 9)),
                StandardCharsets.UTF_8);
        StringBuilder before = new StringBuilder();

        assertThatThrownBy(() -> {
            char[] buffer = new char[100];
            int read;
            while ((read = reader.read(buffer, 0, buffer.length)) >= 0) {
                before.append(buffer, 0, read);
            }
        }).isInstanceOfSatisfying(DecodingReader.EncodingException.class,
                e -> assertThat(e.line()).isEqualTo(line));
        assertThat(before.toString()).endsWith("abc").hasLineCount(line);
    }

    @Test
    void countsLinesAcrossBufferBoundaries() {
        DecodingReader reader = new DecodingReader(new ByteArrayInputStream(withInvalidByteAtLine(700, 30)),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> readAll(reader))
                .isInstanceOfSatisfying(DecodingReader.EncodingException.class, e -> {
                    assertThat(e.line()).isEqualTo(700);
                    assertThat(e).hasMessage("invalid UTF-8 byte sequence at line 700");
                });
    }

    @Test
    void truncatedMultiByteSequenceAtTheEndIsInvalid() {
        byte[] bytes = {'a', '\n', (byte) 0xC3};

        assertThatThrownBy(() -> readAll(new DecodingReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(DecodingReader.EncodingException.class, e -> assertThat(e.line()).isEqualTo(2));
    }

    @Test
    void emptyInput() throws IOException {
        assertThat(readAll(new DecodingReader(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8))).isEmpty();
    }
}
