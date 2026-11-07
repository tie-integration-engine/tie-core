package org.tie.config;

/**
 * A stream supplied by the caller in the execution context. It skips name patterns, watermarks,
 * the already-processed predicate and post-success actions, and never moves a watermark.
 */
public record ProvidedStreamChannel() implements ChannelDefinition {

    public static final ProvidedStreamChannel INSTANCE = new ProvidedStreamChannel();
}
