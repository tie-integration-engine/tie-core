package org.tie.internal.delivery;

/** Where a file ended up: its final name (with a suffix if there was a collision) and the destination. */
public record Delivered(String fileName, String destination) {
}
