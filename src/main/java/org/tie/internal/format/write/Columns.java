package org.tie.internal.format.write;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.tie.config.ColumnLocation;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;

/**
 * Columns of a CSV/XLSX export (§4.1 "Forma de la salida"): one per field in declaration order, headed
 * by its name (or {@code column} in HEADER mode); with LETTER, each field in its letter and the letters
 * without a field empty (AC-180).
 */
final class Columns {

    private final String[] header;
    private final String[] fields;

    private Columns(String[] header, String[] fields) {
        this.header = header;
        this.fields = fields;
    }

    static Columns of(Layout layout) {
        List<FieldDefinition> fields = layout.fields();
        if (layout.columnLocation() == ColumnLocation.LETTER) {
            int width = 0;
            for (FieldDefinition field : fields) {
                if (field.column() != null) {
                    width = Math.max(width, letterIndex(field.column()) + 1);
                }
            }
            String[] header = new String[width];
            String[] names = new String[width];
            Arrays.fill(header, "");
            for (FieldDefinition field : fields) {
                if (field.column() != null) {
                    int index = letterIndex(field.column());
                    header[index] = field.name();
                    names[index] = field.name();
                }
            }
            return new Columns(header, names);
        }
        List<String> header = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (FieldDefinition field : fields) {
            header.add(layout.columnLocation() == ColumnLocation.HEADER && field.column() != null ? field.column()
                    : field.name());
            names.add(field.name());
        }
        return new Columns(header.toArray(String[]::new), names.toArray(String[]::new));
    }

    List<String> header() {
        return Arrays.asList(header);
    }

    /** Field name of each column, null for an empty LETTER column. */
    List<String> fields() {
        return Arrays.asList(fields);
    }

    private static int letterIndex(String letter) {
        int index = 0;
        for (int i = 0; i < letter.length(); i++) {
            index = index * 26 + (Character.toUpperCase(letter.charAt(i)) - 'A' + 1);
        }
        return index - 1;
    }
}
