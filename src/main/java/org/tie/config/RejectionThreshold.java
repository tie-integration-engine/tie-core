package org.tie.config;

import java.math.BigDecimal;

/**
 * Per-file rejection limits; null means no limit. A file fails when it <em>exceeds</em> a limit.
 * The percentage requires {@link DeliveryStrategy#VALIDATE_THEN_DELIVER}.
 */
public record RejectionThreshold(Integer maxRejected, BigDecimal maxRejectedPercent) {

    public static final RejectionThreshold NONE = new RejectionThreshold(null, null);

    public static RejectionThreshold maxRejected(int maxRejected) {
        return new RejectionThreshold(maxRejected, null);
    }

    public static RejectionThreshold maxRejectedPercent(BigDecimal maxRejectedPercent) {
        return new RejectionThreshold(null, maxRejectedPercent);
    }
}
