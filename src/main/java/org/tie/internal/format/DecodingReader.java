package org.tie.internal.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * A strict decoding {@link Reader}: it strips a leading byte order mark, returns every character
 * decoded before an invalid byte sequence, and only then fails with an {@link EncodingException}
 * that knows the physical line (1-based) of the invalid bytes (AC-33, AC-34).
 */
public final class DecodingReader extends Reader {

    private static final char BOM = '﻿';

    private final InputStream in;
    private final Charset charset;
    private final CharsetDecoder decoder;
    private final ByteBuffer bytes = ByteBuffer.allocate(8192);
    private final CharBuffer chars = CharBuffer.allocate(8192);
    private boolean endOfInput;
    private boolean finished;
    private boolean start = true;
    private int newlines;
    private EncodingException pending;

    public DecodingReader(InputStream in, Charset charset) {
        this.in = in;
        this.charset = charset;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        bytes.flip();
        chars.flip();
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (!chars.hasRemaining()) {
            if (pending != null) {
                throw pending;
            }
            if (finished) {
                return -1;
            }
            decodeMore();
        }
        int count = Math.min(length, chars.remaining());
        chars.get(buffer, offset, count);
        return count;
    }

    private void decodeMore() throws IOException {
        chars.clear();
        CoderResult result = CoderResult.UNDERFLOW;
        while (chars.position() == 0 && pending == null && !finished) {
            if (!bytes.hasRemaining() && !endOfInput) {
                fill();
            }
            result = decoder.decode(bytes, chars, endOfInput);
            if (result.isError()) {
                countNewlines();
                pending = new EncodingException(charset, newlines + 1);
            } else if (endOfInput && result.isUnderflow()) {
                CoderResult flush = decoder.flush(chars);
                if (flush.isError()) {
                    countNewlines();
                    pending = new EncodingException(charset, newlines + 1);
                } else {
                    finished = true;
                }
            } else if (result.isUnderflow() && !endOfInput) {
                fill();
            }
        }
        if (pending == null) {
            countNewlines();
        }
        chars.flip();
        if (start && chars.hasRemaining()) {
            start = false;
            if (chars.get(0) == BOM) {
                chars.get();
            }
        }
    }

    /** Counts the newlines decoded in this round (positions 0..position of {@link #chars}). */
    private void countNewlines() {
        for (int i = 0; i < chars.position(); i++) {
            if (chars.get(i) == '\n') {
                newlines++;
            }
        }
    }

    private void fill() throws IOException {
        bytes.compact();
        int read = in.read(bytes.array(), bytes.position(), bytes.remaining());
        if (read < 0) {
            endOfInput = true;
        } else {
            bytes.position(bytes.position() + read);
        }
        bytes.flip();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Invalid bytes for the declared encoding. */
    public static final class EncodingException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int line;

        EncodingException(Charset charset, int line) {
            super("invalid " + charset.name() + " byte sequence at line " + line);
            this.line = line;
        }

        /** Physical line (1-based) of the invalid bytes. */
        public int line() {
            return line;
        }
    }
}
