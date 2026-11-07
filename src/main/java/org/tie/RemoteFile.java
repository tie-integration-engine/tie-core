package org.tie;

import java.time.Instant;

/** A file as listed by an input channel; {@code lastModified} may be null if the channel does not know it. */
public record RemoteFile(String name, long size, Instant lastModified) {
}
