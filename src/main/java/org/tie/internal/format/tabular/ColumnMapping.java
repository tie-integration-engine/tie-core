package org.tie.internal.format.tabular;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.tie.Warning;
import org.tie.WarningCode;
import org.tie.config.ColumnLocation;
import org.tie.config.FieldDefinition;
import org.tie.config.Layout;
import org.tie.internal.engine.LineInput;
import org.tie.internal.format.FileFailure;

/**
 * Maps the columns of a CSV/XLSX file to the fields of a layout, once per file (§4.1
 * {@code columnLocation}). Fields with {@code inInput = false} never consume a column (AC-65).
 *
 * <p>Raw value keys are the header names when there is a header (empty or repeated names fall back
 * to the position), otherwise {@code column1, column2…} or, with LETTER, the column letter.
 */
final class ColumnMapping {

    private static final int NO_COLUMN = -1;

    private final List<FieldDefinition> inputFields;
    private final int[] fieldColumns;
    private final List<String> header;
    private final ColumnLocation location;
    private final boolean canonical;
    private final Consumer<Warning> warnings;
    private final String fileName;
    private final int maxFieldColumn;
    private boolean widthChecked;

    private ColumnMapping(List<FieldDefinition> inputFields, int[] fieldColumns, List<String> header,
                          ColumnLocation location, boolean canonical, Consumer<Warning> warnings, String fileName) {
        this.inputFields = inputFields;
        this.fieldColumns = fieldColumns;
        this.header = header;
        this.location = location;
        this.canonical = canonical;
        this.warnings = warnings;
        this.fileName = fileName;
        int max = NO_COLUMN;
        for (int column : fieldColumns) {
            max = Math.max(max, column);
        }
        this.maxFieldColumn = max;
    }

    /**
     * @param header          header cells, or null when the file has no header
     * @param headerRowNumber physical row of the header, for structural errors
     * @throws FileFailure if the header does not match the layout in HEADER mode
     */
    static ColumnMapping create(Layout layout, List<String> header, int headerRowNumber, boolean canonical,
                                Consumer<Warning> warnings, String fileName) {
        List<FieldDefinition> inputFields = layout.fields().stream().filter(FieldDefinition::inInput).toList();
        boolean passThrough = layout.fields().isEmpty();
        ColumnLocation location = passThrough ? ColumnLocation.POSITION : layout.columnLocation();
        int[] columns = new int[inputFields.size()];
        ColumnMapping mapping;
        switch (location) {
            case POSITION -> {
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = i;
                }
                mapping = new ColumnMapping(inputFields, columns, header, location, canonical, warnings, fileName);
                if (!passThrough) {
                    mapping.checkPositionHeader();
                }
            }
            case LETTER -> {
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = letterIndex(inputFields.get(i).column());
                }
                mapping = new ColumnMapping(inputFields, columns, header, location, canonical, warnings, fileName);
            }
            case HEADER -> {
                mapHeader(layout, inputFields, header == null ? List.of() : header, headerRowNumber, columns,
                        warnings, fileName);
                mapping = new ColumnMapping(inputFields, columns, header, location, canonical, warnings, fileName);
            }
            default -> throw new IllegalStateException(location.name());
        }
        if (passThrough) {
            mapping.widthChecked = true;
        } else if (header != null) {
            mapping.checkWidth(header.size());
        }
        return mapping;
    }

    LineInput map(TabularRow row) {
        List<String> cells = row.cells();
        if (!widthChecked) {
            checkWidth(cells.size());
        }
        Map<String, String> fieldInputs = new LinkedHashMap<>();
        for (int i = 0; i < inputFields.size(); i++) {
            int column = fieldColumns[i];
            fieldInputs.put(inputFields.get(i).name(), column >= 0 && column < cells.size() ? cells.get(column) : null);
        }
        Map<String, String> rawValues = new LinkedHashMap<>();
        List<String> keys = rawKeys(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            rawValues.put(keys.get(i), cells.get(i));
        }
        return new LineInput(fieldInputs, rawValues, canonical);
    }

    // ---------------------------------------------------------------- header checks

    private void checkPositionHeader() {
        if (header == null) {
            return;
        }
        for (int i = 0; i < inputFields.size() && i < header.size(); i++) {
            String expected = inputFields.get(i).name();
            String found = header.get(i).strip();
            if (!found.equalsIgnoreCase(expected)) {
                warn(WarningCode.HEADER_MISMATCH, "header mismatch at column " + (i + 1) + ": expected '" + expected
                        + "', found '" + found + "'");
            }
        }
    }

    /** Warns once about columns beyond the mapped ones or fields beyond the file's columns. */
    private void checkWidth(int width) {
        widthChecked = true;
        if (location == ColumnLocation.POSITION && width > inputFields.size()) {
            warn(WarningCode.EXTRA_COLUMNS, "the file has " + width + " columns and only " + inputFields.size()
                    + " are mapped; the extra columns are ignored");
        }
        if (maxFieldColumn >= width) {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < inputFields.size(); i++) {
                if (fieldColumns[i] >= width) {
                    missing.add(inputFields.get(i).name());
                }
            }
            warn(WarningCode.MISSING_COLUMN, "the file has " + width + " columns; fields " + String.join(", ", missing)
                    + " have no column and are empty");
        }
    }

    private static void mapHeader(Layout layout, List<FieldDefinition> inputFields, List<String> header,
                                  int headerRowNumber, int[] columns, Consumer<Warning> warnings, String fileName) {
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = normalize(header.get(i));
            if (name.isEmpty()) {
                warnings.accept(new Warning(WarningCode.EXTRA_COLUMNS,
                        "column " + (i + 1) + " has no header and is ignored", fileName));
                continue;
            }
            Integer previous = byName.putIfAbsent(name, i);
            if (previous != null) {
                throw FileFailure.structural("duplicate header '" + header.get(i).strip() + "' at columns "
                        + (previous + 1) + " and " + (i + 1), headerRowNumber);
            }
        }
        Set<String> known = new HashSet<>();
        for (FieldDefinition field : layout.fields()) {
            known.add(normalize(headerName(field)));
        }
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = normalize(header.get(i));
            if (!name.isEmpty() && !known.contains(name)) {
                unknown.add(header.get(i).strip());
            }
        }
        if (!unknown.isEmpty()) {
            throw FileFailure.structural("header columns not configured in layout '" + layout.name() + "': "
                    + String.join(", ", unknown), headerRowNumber);
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < inputFields.size(); i++) {
            FieldDefinition field = inputFields.get(i);
            Integer column = byName.get(normalize(headerName(field)));
            columns[i] = column == null ? NO_COLUMN : column;
            if (column == null && !field.optional()) {
                missing.add(field.name());
            }
        }
        if (!missing.isEmpty()) {
            throw FileFailure.structural("fields without a column in the header: " + String.join(", ", missing),
                    headerRowNumber);
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<String> rawKeys(int width) {
        List<String> keys = new ArrayList<>(width);
        Set<String> used = new HashSet<>();
        for (int i = 0; i < width; i++) {
            String key = null;
            if (header != null && i < header.size()) {
                String name = header.get(i).strip();
                if (!name.isEmpty() && used.add(name)) {
                    key = name;
                }
            }
            if (key == null) {
                key = location == ColumnLocation.LETTER ? letter(i) : "column" + (i + 1);
                used.add(key);
            }
            keys.add(key);
        }
        return keys;
    }

    private static String headerName(FieldDefinition field) {
        return field.column() != null ? field.column() : field.name();
    }

    private static String normalize(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** Zero-based index of a column letter ({@code A} is 0, {@code AA} is 26). */
    static int letterIndex(String letter) {
        int index = 0;
        for (int i = 0; i < letter.length(); i++) {
            index = index * 26 + (letter.charAt(i) - 'A' + 1);
        }
        return index - 1;
    }

    static String letter(int index) {
        StringBuilder letter = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            letter.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return letter.toString();
    }

    private void warn(WarningCode code, String message) {
        warnings.accept(new Warning(code, message, fileName));
    }
}
