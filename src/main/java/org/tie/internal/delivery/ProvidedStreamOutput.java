package org.tie.internal.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.tie.Warning;
import org.tie.config.OnExistingFile;
import org.tie.internal.channel.ChannelException;

/** Delivery to the caller's stream (export to a {@code ProvidedStreamChannel}); TIE closes the stream. */
public final class ProvidedStreamOutput implements OutputChannel {

    public static final String DESTINATION = "provided output stream";

    private final Supplier<OutputStream> output;

    public ProvidedStreamOutput(Supplier<OutputStream> output) {
        this.output = output;
    }

    @Override
    public Delivered deliver(Path file, String name, OnExistingFile onExistingFile, Consumer<Warning> warnings)
            throws ChannelException {
        try (OutputStream out = output.get()) {
            Files.copy(file, out);
        } catch (IOException | RuntimeException e) {
            throw new ChannelException("cannot write " + name + " to the provided output: " + e.getMessage(), e);
        }
        return new Delivered(name, DESTINATION);
    }

    @Override
    public void close() {
        // the stream is closed after each delivery
    }
}
