package org.tie;

import java.io.InputStream;
import java.util.Objects;

/**
 * A stream supplied by the caller for a {@code ProvidedStreamChannel} (§5.2). It skips name patterns,
 * watermarks, the already-processed predicate and post-success actions, but not file hooks; TIE closes
 * the stream when its file ends.
 */
public record ProvidedInput(String layout, String fileName, InputStream stream) {

    public ProvidedInput {
        Objects.requireNonNull(stream, "stream");
    }
}
