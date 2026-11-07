package org.tie.internal.format.tree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;
import org.tie.internal.engine.LineInput;

/** Shared rules of the JSON and XML readers (§4.1 "Rutas en JSON y XML"). */
final class TreeLayouts {

    private final List<Layout> layouts;

    TreeLayouts(List<Layout> layouts) {
        this.layouts = List.copyOf(layouts);
    }

    /** Child layouts of {@code parent}, keyed by their nested key path, in declaration order. */
    Map<String, Layout> children(Layout parent) {
        Map<String, Layout> children = new LinkedHashMap<>();
        for (Layout layout : layouts) {
            if (parent.name().equals(layout.parent())) {
                children.put(layout.nestedKey(), layout);
            }
        }
        return children;
    }

    /** Field inputs looked up by path ({@code column}, or the field name), from the flattened values. */
    static LineInput input(Layout layout, Map<String, String> values) {
        Map<String, String> fieldInputs = new LinkedHashMap<>();
        for (FieldDefinition field : layout.fields()) {
            if (field.inInput()) {
                fieldInputs.put(field.name(), values.get(field.column() != null ? field.column() : field.name()));
            }
        }
        return new LineInput(fieldInputs, values, false);
    }

    static String path(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "." + name;
    }
}
