package org.tie.config;

/** Where files come from or go to. */
public sealed interface ChannelDefinition permits SftpChannel, LocalDirectoryChannel, ProvidedStreamChannel {
}
