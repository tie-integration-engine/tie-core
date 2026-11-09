package org.tie.spi;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.tie.RecordView;
import org.tie.config.Direction;

/** What a field expression sees (§4.3 «Expresiones», §4.4). Read-only. */
public interface ExpressionContext {

    /** The field's own value: on import, after steps 1-9; on export, the supplied value. Null if empty. */
    Object value();

    /** The values of the record; fields with an expression already evaluated show their result. */
    RecordView record();

    /**
     * The names of the fields of the record's layout, in declaration order, whether present or not: lets an
     * engine tell a misspelt name from a field that is empty or absent.
     */
    Set<String> fields();

    /**
     * The parent record of a nested record, with its final values; null for a root record and when the
     * parent was rejected. Its {@link #value()} is null.
     */
    ExpressionContext parent();

    /** Today, from the clock of {@code Tie}. */
    LocalDate today();

    /** The variables of the import or export context. */
    Map<String, String> variables();

    Direction direction();
}
