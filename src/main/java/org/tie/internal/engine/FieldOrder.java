package org.tie.internal.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.tie.config.FieldDefinition;

/** Declaration order, except that a copy source always comes before its copies (INV-6). */
final class FieldOrder {

    private FieldOrder() {
    }

    /**
     * @throws IllegalStateException on a copy cycle or an unknown source (a validated layout has none)
     */
    static List<FieldDefinition> of(List<FieldDefinition> fields) {
        Map<String, FieldDefinition> byKey = new LinkedHashMap<>();
        fields.forEach(field -> byKey.put(key(field.name()), field));
        List<FieldDefinition> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String key : byKey.keySet()) {
            visit(key, byKey, visited, new HashSet<>(), order);
        }
        return List.copyOf(order);
    }

    private static void visit(String key, Map<String, FieldDefinition> byKey, Set<String> visited,
                              Set<String> inProgress, List<FieldDefinition> order) {
        if (visited.contains(key)) {
            return;
        }
        if (!inProgress.add(key)) {
            throw new IllegalStateException("copy cycle through field '" + key + "'");
        }
        FieldDefinition field = byKey.get(key);
        if (field.copyFrom() != null) {
            if (!byKey.containsKey(key(field.copyFrom()))) {
                throw new IllegalStateException("copyFrom '" + field.copyFrom() + "' does not exist");
            }
            visit(key(field.copyFrom()), byKey, visited, inProgress, order);
        }
        visited.add(key);
        order.add(field);
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
