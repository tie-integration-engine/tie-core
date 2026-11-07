package org.tie.spi;

import java.util.Objects;

/** What the consumer did with a record: applied, or rejected with a reason (A4). */
public sealed interface Outcome {

    Outcome APPLIED = new Applied();

    static Outcome applied() {
        return APPLIED;
    }

    static Outcome rejected(String reason) {
        return new Rejected(reason);
    }

    record Applied() implements Outcome {
    }

    record Rejected(String reason) implements Outcome {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
