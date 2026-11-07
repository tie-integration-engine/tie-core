package org.tie.internal.run;

import java.util.Map;
import org.tie.TypedRecord;
import org.tie.internal.engine.FieldValues;

/** The {@link TypedRecord} handed to the consumer. */
record DefaultTypedRecord(FieldValues values, String layout, String sourceFileName, int lineNumber,
                          Integer parentLineNumber, Map<String, Boolean> declaredChildren) implements TypedRecord {

    DefaultTypedRecord {
        declaredChildren = Map.copyOf(declaredChildren);
    }

    @Override
    public boolean has(String field) {
        return values.has(field);
    }

    @Override
    public Object get(String field) {
        return values.get(field);
    }

    @Override
    public <T> T get(String field, Class<T> type) {
        return values.get(field, type);
    }

    @Override
    public Map<String, String> rawValues() {
        return values.rawValues();
    }

    @Override
    public boolean childrenDeclared() {
        return declaredChildren.containsValue(true);
    }

    @Override
    public boolean childrenDeclared(String childLayout) {
        return declaredChildren.getOrDefault(childLayout, false);
    }

    @Override
    public String toString() {
        return "TypedRecord[" + layout + " " + sourceFileName + ":" + lineNumber + "]";
    }
}
