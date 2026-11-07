package org.tie.config;

/** How an import delivers records to the consumer. */
public enum DeliveryStrategy {
    STREAMING,
    VALIDATE_THEN_DELIVER
}
