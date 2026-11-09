package org.tie.internal.validation;

import static org.tie.internal.validation.Labels.isBlank;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.tie.config.ChannelDefinition;
import org.tie.config.ColumnLocation;
import org.tie.config.CsvOptions;
import org.tie.config.DeliveryStrategy;
import org.tie.config.Direction;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnEmpty;
import org.tie.config.OutputAs;
import org.tie.config.PostSuccessAction;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.RejectionThreshold;
import org.tie.config.ReportMode;
import org.tie.config.ResultReport;
import org.tie.config.SftpChannel;
import org.tie.config.TextTransform;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.TypeFormat;
import org.tie.config.Validations;
import org.tie.internal.pattern.NamePattern;
import org.tie.internal.pattern.NamePatternException;
import org.tie.internal.secret.SecretReference;

/**
 * Validates an {@link IntegrationDefinition} on its own (no SPI registry, no execution context) and
 * returns every problem found, in a stable order (INV-3).
 */
public final class DefinitionValidator {

    static final String PERCENT_REQUIRES_VALIDATE = "percentage threshold requires VALIDATE_THEN_DELIVER";
    static final String NESTED_EXPORT_FORMATS = "nested layouts are only supported in JSON and XML exports";
    static final String NESTED_IMPORT_FORMATS = "nested layouts are only supported in JSON and XML";
    static final String FIRST_ROW_WITH_HEADER = "firstDataRow must be at least 2 when hasHeader is true";

    private static final Pattern COLUMN_LETTER = Pattern.compile("[A-Z]{1,3}");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final List<String> problems = new ArrayList<>();

    private DefinitionValidator() {
    }

    public static List<String> validate(IntegrationDefinition definition) {
        DefinitionValidator validator = new DefinitionValidator();
        validator.run(definition);
        return List.copyOf(validator.problems);
    }

    /** Problems of one output channel on its own, for {@code deliverFile}. */
    public static List<String> validateChannel(String path, ChannelDefinition channel) {
        DefinitionValidator validator = new DefinitionValidator();
        validator.channel(path, channel);
        return List.copyOf(validator.problems);
    }

    private void problem(String problem) {
        problems.add(problem);
    }

    private void run(IntegrationDefinition definition) {
        if (definition == null) {
            problem("definition is required");
            return;
        }
        if (isBlank(definition.id())) {
            problem("id is required");
        }
        Direction direction = definition.direction();
        if (direction == null) {
            problem("direction is required");
        }
        layouts(definition);
        if (direction == Direction.IMPORT) {
            importSettings(definition);
        } else if (direction == Direction.EXPORT) {
            exportSettings(definition);
        }
        threshold(definition);
    }

    // ---------------------------------------------------------------- layouts

    private void layouts(IntegrationDefinition definition) {
        List<Layout> layouts = definition.layouts();
        if (layouts.isEmpty()) {
            problem("at least one layout is required");
            return;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < layouts.size(); i++) {
            Layout layout = layouts.get(i);
            String label = Labels.layout(layout, i);
            if (isBlank(layout.name())) {
                problem(label + ": name is required");
            } else if (!names.add(layout.name())) {
                problem(label + ": duplicate layout name");
            }
        }
        for (int i = 0; i < layouts.size(); i++) {
            layout(definition, layouts.get(i), Labels.layout(layouts.get(i), i), names);
        }
        nestedKeys(layouts);
        parentCycles(definition);
    }

    private void layout(IntegrationDefinition definition, Layout layout, String label, Set<String> names) {
        boolean export = definition.direction() == Direction.EXPORT;
        boolean tabular = false;
        if (layout.isRoot()) {
            if (export) {
                if (layout.fileNamePattern() != null) {
                    problem(label + ": fileNamePattern does not apply to exports (use outputNamePattern)");
                }
            } else if (isBlank(layout.fileNamePattern())) {
                problem(label + ": fileNamePattern is required");
            } else {
                pattern(label + ": fileNamePattern", layout.fileNamePattern());
            }
            if (layout.allowedFormats().isEmpty()) {
                problem(label + ": at least one allowed format is required");
            } else if (export && layout.allowedFormats().size() > 1) {
                problem(label + ": an export layout must allow exactly one format");
            }
            formatOptions(layout, label, export);
            tabular = layout.allowedFormats().stream().anyMatch(FileFormat::isTabular);
            boolean hasChildren = definition.layouts().stream()
                    .anyMatch(other -> layout.name() != null && layout.name().equals(other.parent()));
            if (hasChildren && tabular) {
                problem(label + ": " + (export ? NESTED_EXPORT_FORMATS : NESTED_IMPORT_FORMATS));
            }
        } else {
            if (layout.fileNamePattern() != null) {
                problem(label + ": fileNamePattern does not apply to child layouts");
            }
            if (!names.contains(layout.parent())) {
                problem(label + ": parent '" + layout.parent() + "' does not exist");
            }
            if (isBlank(layout.nestedKey())) {
                problem(label + ": nestedKey is required for a child layout");
            }
        }
        fields(layout, label, tabular);
    }

    private void formatOptions(Layout layout, String label, boolean export) {
        Set<FileFormat> formats = layout.allowedFormats();
        if (formats.contains(FileFormat.CSV)) {
            CsvOptions csv = layout.formatOptions().csv();
            rows(label + ": csv", csv.hasHeader(), csv.firstDataRow());
            if (csv.separator() == csv.quote()) {
                problem(label + ": csv separator and quote must be different");
            }
            if (isLineBreak(csv.separator()) || isLineBreak(csv.quote())) {
                problem(label + ": csv separator and quote must not be line breaks");
            }
        }
        if (formats.contains(FileFormat.XLSX)) {
            rows(label + ": xlsx", layout.formatOptions().xlsx().hasHeader(), layout.formatOptions().xlsx().firstDataRow());
            String sheet = layout.formatOptions().xlsx().sheet();
            if (sheet != null && sheet.isBlank()) {
                problem(label + ": xlsx sheet must not be blank");
            }
        }
        if (formats.contains(FileFormat.XML)) {
            String recordElement = layout.formatOptions().xml().recordElement();
            if (export ? isBlank(recordElement) : recordElement != null && recordElement.isBlank()) {
                problem(label + (export ? ": xml recordElement is required" : ": xml recordElement must not be blank"));
            }
            if (export && isBlank(layout.formatOptions().xml().rootElement())) {
                problem(label + ": xml rootElement is required");
            }
        }
        if (formats.contains(FileFormat.JSON)) {
            String recordProperty = layout.formatOptions().json().recordProperty();
            if (recordProperty != null && recordProperty.isBlank()) {
                problem(label + ": json recordProperty must not be blank");
            }
            if (!export && recordProperty != null && layout.formatOptions().json().firstArray()) {
                problem(label + ": json recordProperty and firstArray cannot be combined");
            }
        }
        boolean csvWithoutHeader = formats.contains(FileFormat.CSV) && !layout.formatOptions().csv().hasHeader();
        boolean xlsxWithoutHeader = formats.contains(FileFormat.XLSX) && !layout.formatOptions().xlsx().hasHeader();
        if (layout.columnLocation() == ColumnLocation.HEADER && !layout.fields().isEmpty()
                && (csvWithoutHeader || xlsxWithoutHeader)) {
            problem(label + ": HEADER column location requires hasHeader (set columnLocation to POSITION or LETTER)");
        }
    }

    private void rows(String label, boolean hasHeader, int firstDataRow) {
        if (firstDataRow < 1) {
            problem(label + ": firstDataRow must be at least 1");
        } else if (hasHeader && firstDataRow < 2) {
            problem(label + ": " + FIRST_ROW_WITH_HEADER);
        }
    }

    private void nestedKeys(List<Layout> layouts) {
        Map<String, Set<String>> keysByParent = new HashMap<>();
        for (int i = 0; i < layouts.size(); i++) {
            Layout layout = layouts.get(i);
            if (layout.isRoot() || isBlank(layout.nestedKey())) {
                continue;
            }
            if (!keysByParent.computeIfAbsent(layout.parent(), key -> new HashSet<>()).add(layout.nestedKey())) {
                problem(Labels.layout(layout, i) + ": duplicate nestedKey '" + layout.nestedKey()
                        + "' under parent '" + layout.parent() + "'");
            }
        }
    }

    private void parentCycles(IntegrationDefinition definition) {
        Set<Set<String>> reported = new HashSet<>();
        for (Layout start : definition.layouts()) {
            List<String> path = new ArrayList<>();
            Layout current = start;
            while (current != null && !current.isRoot() && current.name() != null) {
                int seen = path.indexOf(current.name());
                if (seen >= 0) {
                    List<String> cycle = path.subList(seen, path.size());
                    if (reported.add(Set.copyOf(cycle))) {
                        problem("layout '" + cycle.getFirst() + "': parent cycle " + String.join(" -> ", cycle)
                                + " -> " + cycle.getFirst());
                    }
                    break;
                }
                path.add(current.name());
                current = definition.layout(current.parent()).orElse(null);
            }
        }
    }

    // ---------------------------------------------------------------- fields

    private void fields(Layout layout, String label, boolean tabular) {
        List<FieldDefinition> fields = layout.fields();
        Map<String, FieldDefinition> byName = new LinkedHashMap<>();
        for (int j = 0; j < fields.size(); j++) {
            FieldDefinition field = fields.get(j);
            String fieldLabel = Labels.field(label, field, j);
            if (isBlank(field.name())) {
                problem(fieldLabel + ": name is required");
            } else if (byName.putIfAbsent(key(field.name()), field) != null) {
                problem(fieldLabel + ": duplicate field name (field names are case-insensitive)");
            }
        }
        for (int j = 0; j < fields.size(); j++) {
            field(layout, fields.get(j), Labels.field(label, fields.get(j), j), byName, tabular);
        }
        copyCycles(label, fields, byName);
    }

    private void field(Layout layout, FieldDefinition field, String label, Map<String, FieldDefinition> byName,
                       boolean tabular) {
        if (tabular && field.inInput() && layout.columnLocation() == ColumnLocation.LETTER) {
            if (field.column() == null) {
                problem(label + ": column letter is required with LETTER column location");
            } else if (!COLUMN_LETTER.matcher(field.column()).matches()) {
                problem(label + ": column '" + field.column() + "' is not a valid letter (A to ZZZ)");
            }
        }
        if (field.optional() && layout.columnLocation() != ColumnLocation.HEADER) {
            problem(label + ": optional only applies with HEADER column location");
        }
        if (field.copyFrom() != null && !byName.containsKey(key(field.copyFrom()))) {
            problem(label + ": copyFrom '" + field.copyFrom() + "' does not exist");
        }
        if (field.copyMode() != null && field.copyFrom() == null) {
            problem(label + ": copyMode requires copyFrom");
        }
        if (field.transformer() != null && field.transformer().isBlank()) {
            problem(label + ": transformer name must not be blank");
        }
        textTransforms(field, label);
        translations(field, label);
        typeFormat(field, label);
        validations(field.validations(), label);
        if (field.onEmpty() == OnEmpty.DEFAULT && field.defaultValue() == null) {
            problem(label + ": onEmpty DEFAULT requires defaultValue");
        } else if (field.onEmpty() != OnEmpty.DEFAULT && field.defaultValue() != null) {
            problem(label + ": defaultValue only applies with onEmpty DEFAULT");
        }
        if (field.defaultMode() != null && field.defaultValue() == null) {
            problem(label + ": defaultMode requires defaultValue");
        }
        if (field.outputAs() == OutputAs.NATIVE && field.hasTextualExportSteps()) {
            problem(label + ": outputAs NATIVE cannot be combined with typeFormat, textTransforms, translations"
                    + " or transformer");
        }
    }

    private void textTransforms(FieldDefinition field, String label) {
        for (TextTransform transform : field.textTransforms()) {
            int padLength = switch (transform) {
                case null -> {
                    problem(label + ": textTransforms must not contain null");
                    yield 1;
                }
                case TextTransform.PadLeft pad -> pad.length();
                case TextTransform.PadRight pad -> pad.length();
                default -> 1;
            };
            if (padLength < 1) {
                problem(label + ": padding length must be at least 1");
            }
        }
    }

    private void translations(FieldDefinition field, String label) {
        Set<String> importKeys = new HashSet<>();
        Set<String> exportKeys = new HashSet<>();
        List<Translation> translations = field.translations();
        for (int k = 0; k < translations.size(); k++) {
            Translation translation = translations.get(k);
            if (translation == null || translation.from() == null) {
                problem(label + ": translation #" + (k + 1) + ": from is required");
                continue;
            }
            TranslationDirection direction = translation.direction();
            if (direction != TranslationDirection.OUT && !importKeys.add(translation.from())) {
                problem(label + ": translation from '" + translation.from() + "' is defined more than once for import");
            }
            String exportKey = direction == TranslationDirection.BOTH ? translation.to() : translation.from();
            if (direction != TranslationDirection.IN && exportKey != null && !exportKeys.add(exportKey)) {
                problem(label + ": translation for '" + exportKey + "' is defined more than once for export");
            }
        }
    }

    private void typeFormat(FieldDefinition field, String label) {
        FieldType type = field.type();
        switch (field.typeFormat()) {
            case null -> {
            }
            case TypeFormat.DateFormat(String pattern, Locale ignored) -> {
                if (type != FieldType.DATE && type != FieldType.DATETIME) {
                    problem(label + ": a date format only applies to DATE and DATETIME");
                }
                if (isBlank(pattern)) {
                    problem(label + ": date format pattern is required");
                } else {
                    try {
                        DateTimeFormatter.ofPattern(pattern);
                    } catch (IllegalArgumentException e) {
                        problem(label + ": invalid date format '" + pattern + "': " + e.getMessage());
                    }
                }
            }
            case TypeFormat.DecimalFormat(Character decimal, Character grouping) -> {
                if (type != FieldType.INTEGER && type != FieldType.DECIMAL) {
                    problem(label + ": a decimal format only applies to INTEGER and DECIMAL");
                }
                if (decimal != null && decimal.equals(grouping)) {
                    problem(label + ": decimal and grouping separators must be different");
                }
            }
            case TypeFormat.BooleanFormat(String trueLiteral, String falseLiteral) -> {
                if (type != FieldType.BOOLEAN) {
                    problem(label + ": boolean literals only apply to BOOLEAN");
                }
                if (isBlank(trueLiteral) || isBlank(falseLiteral)) {
                    problem(label + ": boolean literals must not be blank");
                } else if (trueLiteral.equals(falseLiteral)) {
                    problem(label + ": boolean literals must be different");
                }
            }
        }
    }

    private void validations(Validations validations, String label) {
        Integer min = validations.minLength();
        Integer max = validations.maxLength();
        if (min != null && min < 0) {
            problem(label + ": minLength must not be negative");
        }
        if (max != null && max < 1) {
            problem(label + ": maxLength must be at least 1");
        }
        if (min != null && max != null && min > max) {
            problem(label + ": minLength must not be greater than maxLength");
        }
        if (validations.regex() != null) {
            try {
                Pattern.compile(validations.regex());
            } catch (PatternSyntaxException e) {
                problem(label + ": invalid regex '" + validations.regex() + "': " + e.getDescription());
            }
        }
        if (validations.allowedValues() != null && validations.allowedValues().isEmpty()) {
            problem(label + ": allowedValues must not be empty");
        }
    }

    /** Copies form a functional graph (one source per field): every cycle is found by walking it. */
    private void copyCycles(String layoutLabel, List<FieldDefinition> fields, Map<String, FieldDefinition> byName) {
        Set<String> done = new HashSet<>();
        for (FieldDefinition start : fields) {
            if (start.name() == null) {
                continue;
            }
            List<String> path = new ArrayList<>();
            FieldDefinition current = start;
            while (current != null && current.name() != null && !done.contains(key(current.name()))) {
                int seen = indexOfIgnoreCase(path, current.name());
                if (seen >= 0) {
                    List<String> cycle = path.subList(seen, path.size());
                    problem(layoutLabel + ": copy cycle " + String.join(" -> ", cycle) + " -> " + cycle.getFirst());
                    break;
                }
                path.add(current.name());
                current = current.copyFrom() == null ? null : byName.get(key(current.copyFrom()));
            }
            path.forEach(name -> done.add(key(name)));
        }
    }

    // ---------------------------------------------------------------- direction-specific settings

    private void importSettings(IntegrationDefinition definition) {
        List<ChannelDefinition> inputs = definition.inputChannels();
        if (inputs.isEmpty()) {
            problem("at least one input channel is required");
        }
        long provided = inputs.stream().filter(ProvidedStreamChannel.class::isInstance).count();
        if (provided > 1) {
            problem("only one ProvidedStreamChannel input channel is allowed");
        }
        for (int i = 0; i < inputs.size(); i++) {
            channel("inputChannels[" + i + "]", inputs.get(i));
        }
        if (definition.outputChannel() != null) {
            problem("outputChannel does not apply to imports");
        }
        if (definition.outputNamePattern() != null) {
            problem("outputNamePattern does not apply to imports");
        }
        duplicateProtection(definition);
        resultReport(definition.resultReport());
    }

    private void exportSettings(IntegrationDefinition definition) {
        long roots = definition.layouts().stream().filter(Layout::isRoot).count();
        if (!definition.layouts().isEmpty() && roots != 1) {
            problem("an export must have exactly one root layout");
        }
        if (definition.outputChannel() == null) {
            problem("outputChannel is required for exports");
        } else {
            channel("outputChannel", definition.outputChannel());
        }
        if (isBlank(definition.outputNamePattern())) {
            problem("outputNamePattern is required for exports");
        } else {
            pattern("outputNamePattern", definition.outputNamePattern());
        }
        if (!definition.inputChannels().isEmpty()) {
            problem("inputChannels do not apply to exports");
        }
        if (definition.deliveryStrategy() == DeliveryStrategy.VALIDATE_THEN_DELIVER) {
            problem("deliveryStrategy VALIDATE_THEN_DELIVER does not apply to exports");
        }
        if (!definition.duplicateProtection().equals(DuplicateProtection.NONE)) {
            problem("duplicateProtection does not apply to exports");
        }
        if (definition.resultReport().mode() != ReportMode.NONE) {
            problem("resultReport does not apply to exports");
        }
    }

    private void threshold(IntegrationDefinition definition) {
        RejectionThreshold threshold = definition.rejectionThreshold();
        if (threshold.maxRejected() != null && threshold.maxRejected() < 0) {
            problem("rejectionThreshold: maxRejected must not be negative");
        }
        BigDecimal percent = threshold.maxRejectedPercent();
        if (percent != null) {
            if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
                problem("rejectionThreshold: maxRejectedPercent must be between 0 and 100");
            }
            if (definition.deliveryStrategy() != DeliveryStrategy.VALIDATE_THEN_DELIVER) {
                problem("rejectionThreshold: " + PERCENT_REQUIRES_VALIDATE);
            }
        }
    }

    private void duplicateProtection(IntegrationDefinition definition) {
        DuplicateProtection protection = definition.duplicateProtection();
        if (protection.watermark()) {
            List<Layout> layouts = definition.layouts();
            for (int i = 0; i < layouts.size(); i++) {
                Layout layout = layouts.get(i);
                if (!layout.isRoot() || isBlank(layout.fileNamePattern())) {
                    continue;
                }
                try {
                    if (!NamePattern.parse(layout.fileNamePattern()).hasTimestamp()) {
                        problem(Labels.layout(layout, i)
                                + ": watermark requires a {timestamp:…} marker in fileNamePattern");
                    }
                } catch (NamePatternException ignored) {
                    // already reported by the layout check
                }
            }
        }
        if (protection.postSuccessAction() instanceof PostSuccessAction.Move(String folder)) {
            if (isBlank(folder)) {
                problem("duplicateProtection: MOVE requires remoteFolder");
            } else if (folder.contains("\\") || List.of(folder.split("/")).contains("..")) {
                problem("duplicateProtection: MOVE remoteFolder must not contain '..' or '\\'");
            }
        }
    }

    private void resultReport(ResultReport report) {
        if (report.mode() == ReportMode.NONE) {
            return;
        }
        if (report.format() == null) {
            problem("resultReport: format is required");
        }
        if (report.channel() == null) {
            problem("resultReport: channel is required");
        } else if (report.channel() instanceof ProvidedStreamChannel) {
            problem("resultReport: channel cannot be a ProvidedStreamChannel");
        } else {
            channel("resultReport.channel", report.channel());
        }
        if (isBlank(report.namePattern())) {
            problem("resultReport: namePattern is required");
        } else {
            pattern("resultReport: namePattern", report.namePattern());
        }
    }

    // ---------------------------------------------------------------- channels

    private void channel(String path, ChannelDefinition channel) {
        if (channel == null) {
            problem(path + ": channel must not be null");
            return;
        }
        String label = Labels.channel(path, channel);
        switch (channel) {
            case SftpChannel sftp -> sftp(label, sftp);
            case LocalDirectoryChannel local -> {
                if (local.directory() == null) {
                    problem(label + ": directory is required");
                }
                maxFileSize(label, local.maxFileSize());
            }
            case ProvidedStreamChannel ignored -> {
            }
        }
    }

    private void sftp(String label, SftpChannel sftp) {
        if (isBlank(sftp.host())) {
            problem(label + ": host is required");
        }
        if (sftp.port() < 1 || sftp.port() > 65535) {
            problem(label + ": port must be between 1 and 65535");
        }
        if (isBlank(sftp.username())) {
            problem(label + ": username is required");
        }
        int credentials = (sftp.password() != null ? 1 : 0) + (sftp.privateKey() != null ? 1 : 0)
                + (sftp.privateKeyFile() != null ? 1 : 0);
        if (credentials != 1) {
            problem(label + ": exactly one of password, privateKey or privateKeyFile is required");
        }
        if (sftp.privateKeyPassphrase() != null && sftp.privateKey() == null && sftp.privateKeyFile() == null) {
            problem(label + ": privateKeyPassphrase requires privateKey or privateKeyFile");
        }
        secret(label, "password", sftp.password());
        secret(label, "privateKey", sftp.privateKey());
        secret(label, "privateKeyPassphrase", sftp.privateKeyPassphrase());
        if (isBlank(sftp.remoteDirectory())) {
            problem(label + ": remoteDirectory is required");
        }
        boolean knownHosts = sftp.knownHostsFile() != null;
        boolean fingerprint = !isBlank(sftp.hostKeyFingerprint());
        if (knownHosts && fingerprint) {
            problem(label + ": only one of knownHostsFile or hostKeyFingerprint is allowed");
        }
        if (sftp.insecureAcceptAnyHostKey() && (knownHosts || fingerprint)) {
            problem(label + ": insecureAcceptAnyHostKey cannot be combined with knownHostsFile or hostKeyFingerprint");
        }
        if (!sftp.insecureAcceptAnyHostKey() && !knownHosts && !fingerprint) {
            problem(label + ": host key verification requires knownHostsFile or hostKeyFingerprint"
                    + " (or an explicit insecureAcceptAnyHostKey)");
        }
        Duration timeout = sftp.timeout();
        if (timeout.isZero() || timeout.isNegative()) {
            problem(label + ": timeout must be positive");
        }
        maxFileSize(label, sftp.maxFileSize());
    }

    private void secret(String label, String property, String value) {
        if (SecretReference.isMalformed(value)) {
            problem(label + ": " + property + " has a malformed secret reference (expected ${secret:name})");
        }
    }

    private void maxFileSize(String label, Long maxFileSize) {
        if (maxFileSize != null && maxFileSize < 1) {
            problem(label + ": maxFileSize must be at least 1");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void pattern(String label, String pattern) {
        try {
            NamePattern.parse(pattern);
        } catch (NamePatternException e) {
            problem(label + ": " + e.getMessage());
        }
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static String key(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT);
    }

    private static int indexOfIgnoreCase(List<String> names, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
