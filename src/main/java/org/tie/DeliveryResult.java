package org.tie;

import java.util.List;

/** Result of {@code Tie.deliverFile} (§4.2); {@code fileName} carries the collision suffix if one was added. */
public record DeliveryResult(ResultStatus status, String fileName, String destination, FileError error,
                             List<Warning> warnings) {

    public DeliveryResult {
        warnings = List.copyOf(warnings);
    }
}
