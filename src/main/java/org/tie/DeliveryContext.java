package org.tie;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.tie.spi.RunListener;

/** Context of {@code Tie.deliverFile} (§5.2); the listener receives the warnings. */
public record DeliveryContext(Map<String, String> variables, RunListener listener) {

    public static final DeliveryContext EMPTY = new DeliveryContext(Map.of(), null);

    public DeliveryContext {
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables == null ? Map.of() : variables));
    }
}
