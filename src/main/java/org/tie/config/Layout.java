package org.tie.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The structure of a file (root layout) or of a nested sub-record (child layout, with
 * {@code parent} and {@code nestedKey}). Build it with {@link #builder(String)}.
 *
 * <p>{@code columnLocation} defaults to {@link ColumnLocation#HEADER} and {@code onParentRejected}
 * to {@link OnParentRejected#CASCADE}.
 */
public record Layout(
        String name,
        String fileNamePattern,
        Set<FileFormat> allowedFormats,
        FormatOptions formatOptions,
        ColumnLocation columnLocation,
        List<FieldDefinition> fields,
        String parent,
        String nestedKey,
        OnParentRejected onParentRejected) {

    public Layout {
        allowedFormats = allowedFormats == null ? Set.of() : Set.copyOf(allowedFormats);
        formatOptions = formatOptions == null ? FormatOptions.DEFAULTS : formatOptions;
        columnLocation = columnLocation == null ? ColumnLocation.HEADER : columnLocation;
        fields = fields == null ? List.of() : List.copyOf(fields);
        onParentRejected = onParentRejected == null ? OnParentRejected.CASCADE : onParentRejected;
    }

    public static Builder builder(String name) {
        return new Builder().name(name);
    }

    /** Whether this is a root layout (no parent). */
    public boolean isRoot() {
        return parent == null;
    }

    public Builder toBuilder() {
        return new Builder().name(name).fileNamePattern(fileNamePattern).allowedFormats(allowedFormats)
                .formatOptions(formatOptions).columnLocation(columnLocation).fields(fields).parent(parent)
                .nestedKey(nestedKey).onParentRejected(onParentRejected);
    }

    public static final class Builder {
        private String name;
        private String fileNamePattern;
        private Set<FileFormat> allowedFormats = Set.of();
        private FormatOptions formatOptions = FormatOptions.DEFAULTS;
        private ColumnLocation columnLocation;
        private final List<FieldDefinition> fields = new ArrayList<>();
        private String parent;
        private String nestedKey;
        private OnParentRejected onParentRejected;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder fileNamePattern(String fileNamePattern) {
            this.fileNamePattern = fileNamePattern;
            return this;
        }

        public Builder allowedFormats(FileFormat... allowedFormats) {
            return allowedFormats(Set.copyOf(Arrays.asList(allowedFormats)));
        }

        public Builder allowedFormats(Set<FileFormat> allowedFormats) {
            this.allowedFormats = allowedFormats;
            return this;
        }

        public Builder formatOptions(FormatOptions formatOptions) {
            this.formatOptions = formatOptions;
            return this;
        }

        public Builder csv(CsvOptions csv) {
            this.formatOptions = formatOptions.withCsv(csv);
            return this;
        }

        public Builder xlsx(XlsxOptions xlsx) {
            this.formatOptions = formatOptions.withXlsx(xlsx);
            return this;
        }

        public Builder xml(XmlOptions xml) {
            this.formatOptions = formatOptions.withXml(xml);
            return this;
        }

        public Builder json(JsonOptions json) {
            this.formatOptions = formatOptions.withJson(json);
            return this;
        }

        public Builder columnLocation(ColumnLocation columnLocation) {
            this.columnLocation = columnLocation;
            return this;
        }

        public Builder field(FieldDefinition field) {
            this.fields.add(field);
            return this;
        }

        public Builder field(FieldDefinition.Builder field) {
            return field(field.build());
        }

        public Builder fields(List<FieldDefinition> fields) {
            this.fields.clear();
            this.fields.addAll(fields);
            return this;
        }

        /** Makes this a child layout nested under {@code nestedKey} of the {@code parent} layout. */
        public Builder parent(String parent, String nestedKey) {
            this.parent = parent;
            this.nestedKey = nestedKey;
            return this;
        }

        public Builder parent(String parent) {
            this.parent = parent;
            return this;
        }

        public Builder nestedKey(String nestedKey) {
            this.nestedKey = nestedKey;
            return this;
        }

        public Builder onParentRejected(OnParentRejected onParentRejected) {
            this.onParentRejected = onParentRejected;
            return this;
        }

        public Layout build() {
            return new Layout(name, fileNamePattern, allowedFormats, formatOptions, columnLocation, fields, parent,
                    nestedKey, onParentRejected);
        }
    }
}
