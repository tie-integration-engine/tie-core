package org.tie.spi;

import org.tie.RecordView;
import org.tie.config.Direction;

/**
 * Custom field transformation, registered by name and referenced by {@code transformer} (§4.3 step 6,
 * §4.4 step 7). Returning {@code null} makes the value empty; an exception rejects the line with
 * {@code TRANSFORMER_FAILED}. With {@code VALIDATE_THEN_DELIVER} it is called once per pass.
 */
@FunctionalInterface
public interface FieldTransformer {

    String apply(String value, RecordView recordSoFar, Direction direction);
}
