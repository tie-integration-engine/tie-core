package org.tie.internal.delivery;

import java.nio.file.Path;
import java.util.function.Consumer;
import org.tie.Warning;
import org.tie.config.OnExistingFile;
import org.tie.internal.channel.ChannelException;

/**
 * A destination for exports, reports and {@code deliverFile} (§4.1 "Entrega"). The file is never left
 * partially written under its final name (INV-19) and the local file is never modified (INV-17).
 */
public interface OutputChannel extends AutoCloseable {

    /**
     * @param warnings receives non-fatal problems, such as a temporary file that could not be removed
     * @throws ChannelException with type CHANNEL, NAME_COLLISION, AUTHENTICATION or HOST_KEY
     */
    Delivered deliver(Path file, String name, OnExistingFile onExistingFile, Consumer<Warning> warnings)
            throws ChannelException;

    @Override
    void close();
}
