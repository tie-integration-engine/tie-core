package org.tie.internal.load;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import org.tie.config.ChannelDefinition;
import org.tie.config.CsvOptions;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.FormatOptions;
import org.tie.config.IntegrationDefinition;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnEmpty;
import org.tie.config.PostSuccessAction;
import org.tie.config.ProvidedStreamChannel;
import org.tie.config.RejectionThreshold;
import org.tie.config.ResultReport;
import org.tie.config.SftpChannel;
import org.tie.config.TextTransform;
import org.tie.config.Translation;
import org.tie.config.TranslationDirection;
import org.tie.config.TypeFormat;
import org.tie.config.Validations;
import org.tie.config.XlsxOptions;
import org.tie.config.XmlOptions;
import org.tie.internal.secret.SecretReference;
import org.tie.internal.validation.DefinitionValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the document of §5.5 from an {@link IntegrationDefinition}: the inverse of {@link DefinitionMapper}.
 * Keys follow the order of the JSON schema, values that equal what the loader would use when the key is
 * absent are left out, each polymorphic value has one fixed form, and literal credentials are masked
 * (§5.5 «Escribir una definición»).
 */
public final class DefinitionNodes {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DuplicateProtection NO_PROTECTION = DuplicateProtection.NONE;

    private DefinitionNodes() {
    }

    public static ObjectNode document(IntegrationDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ObjectNode doc = NODES.objectNode();
        doc.put("version", DefinitionMapper.SUPPORTED_VERSION);
        putIfNotNull(doc, "id", definition.id());
        if (definition.direction() != null) {
            doc.put("direction", definition.direction().name());
        }
        if (!definition.layouts().isEmpty()) {
            ArrayNode layouts = doc.putArray("layouts");
            definition.layouts().forEach(layout -> layouts.add(layout(layout)));
        }
        if (!definition.inputChannels().isEmpty()) {
            ArrayNode inputs = doc.putArray("inputChannels");
            definition.inputChannels().forEach(channel -> inputs.add(channel(channel)));
        }
        if (definition.outputChannel() != null) {
            doc.set("outputChannel", channel(definition.outputChannel()));
        }
        putIfNotDefault(doc, "deliveryStrategy", definition.deliveryStrategy(), defaults().deliveryStrategy());
        if (!definition.rejectionThreshold().equals(RejectionThreshold.NONE)) {
            ObjectNode threshold = doc.putObject("rejectionThreshold");
            if (definition.rejectionThreshold().maxRejected() != null) {
                threshold.put("maxRejected", definition.rejectionThreshold().maxRejected());
            }
            if (definition.rejectionThreshold().maxRejectedPercent() != null) {
                threshold.put("maxRejectedPercent", definition.rejectionThreshold().maxRejectedPercent());
            }
        }
        putIfNotDefault(doc, "consumerErrorPolicy", definition.consumerErrorPolicy(), defaults().consumerErrorPolicy());
        if (!definition.duplicateProtection().equals(NO_PROTECTION)) {
            ObjectNode protection = doc.putObject("duplicateProtection");
            if (definition.duplicateProtection().watermark()) {
                protection.put("watermark", true);
            }
            if (!(definition.duplicateProtection().postSuccessAction() instanceof PostSuccessAction.None)) {
                protection.set("postSuccessAction", postSuccessAction(definition.duplicateProtection().postSuccessAction()));
            }
        }
        if (!definition.resultReport().equals(ResultReport.NONE)) {
            ResultReport report = definition.resultReport();
            ObjectNode node = doc.putObject("resultReport");
            putIfNotDefault(node, "mode", report.mode(), ResultReport.NONE.mode());
            if (report.format() != null) {
                node.put("format", report.format().name());
            }
            if (report.channel() != null) {
                node.set("channel", channel(report.channel()));
            }
            putIfNotNull(node, "namePattern", report.namePattern());
        }
        putIfNotNull(doc, "outputNamePattern", definition.outputNamePattern());
        return doc;
    }

    // ---------------------------------------------------------------- layouts and fields

    private static ObjectNode layout(Layout layout) {
        Layout defaults = Layout.builder(layout.name()).build();
        ObjectNode node = NODES.objectNode();
        putIfNotNull(node, "name", layout.name());
        putIfNotNull(node, "fileNamePattern", layout.fileNamePattern());
        if (!layout.allowedFormats().isEmpty()) {
            ArrayNode formats = node.putArray("allowedFormats");
            layout.allowedFormats().stream().sorted(Comparator.comparingInt(FileFormat::ordinal))
                    .forEach(format -> formats.add(format.name()));
        }
        ObjectNode options = formatOptions(layout.formatOptions());
        if (!options.isEmpty()) {
            node.set("formatOptions", options);
        }
        putIfNotDefault(node, "columnLocation", layout.columnLocation(), defaults.columnLocation());
        if (!layout.fields().isEmpty()) {
            ArrayNode fields = node.putArray("fields");
            layout.fields().forEach(field -> fields.add(field(field)));
        }
        putIfNotNull(node, "parent", layout.parent());
        putIfNotNull(node, "nestedKey", layout.nestedKey());
        putIfNotDefault(node, "onParentRejected", layout.onParentRejected(), defaults.onParentRejected());
        return node;
    }

    private static ObjectNode formatOptions(FormatOptions options) {
        ObjectNode node = NODES.objectNode();
        CsvOptions csv = options.csv();
        CsvOptions csvDefaults = CsvOptions.DEFAULTS;
        if (!csv.equals(csvDefaults)) {
            ObjectNode n = node.putObject("csv");
            if (csv.separator() != csvDefaults.separator()) {
                n.put("separator", String.valueOf(csv.separator()));
            }
            if (csv.quote() != csvDefaults.quote()) {
                n.put("quote", String.valueOf(csv.quote()));
            }
            if (!csv.encoding().equals(csvDefaults.encoding())) {
                n.put("encoding", csv.encoding().name());
            }
            if (csv.hasHeader() != csvDefaults.hasHeader()) {
                n.put("hasHeader", csv.hasHeader());
            }
            if (csv.firstDataRow() != csvDefaults.firstDataRow()) {
                n.put("firstDataRow", csv.firstDataRow());
            }
            if (csv.skipBlankRecords()) {
                n.put("skipBlankRecords", true);
            }
        }
        XlsxOptions xlsx = options.xlsx();
        XlsxOptions xlsxDefaults = XlsxOptions.DEFAULTS;
        if (!xlsx.equals(xlsxDefaults)) {
            ObjectNode n = node.putObject("xlsx");
            putIfNotNull(n, "sheet", xlsx.sheet());
            if (xlsx.hasHeader() != xlsxDefaults.hasHeader()) {
                n.put("hasHeader", xlsx.hasHeader());
            }
            if (xlsx.firstDataRow() != xlsxDefaults.firstDataRow()) {
                n.put("firstDataRow", xlsx.firstDataRow());
            }
            if (xlsx.skipBlankRecords()) {
                n.put("skipBlankRecords", true);
            }
            if (xlsx.cellValues() != null) {
                n.put("cellValues", xlsx.cellValues().name());
            }
            if (xlsx.locale() != null) {
                n.put("locale", xlsx.locale().toLanguageTag());
            }
        }
        XmlOptions xml = options.xml();
        XmlOptions xmlDefaults = XmlOptions.DEFAULTS;
        if (!xml.equals(xmlDefaults)) {
            ObjectNode n = node.putObject("xml");
            putIfNotNull(n, "recordElement", xml.recordElement());
            if (!xml.rootElement().equals(xmlDefaults.rootElement())) {
                n.put("rootElement", xml.rootElement());
            }
            if (!xml.encoding().equals(xmlDefaults.encoding())) {
                n.put("encoding", xml.encoding().name());
            }
        }
        JsonOptions json = options.json();
        JsonOptions jsonDefaults = JsonOptions.DEFAULTS;
        if (!json.equals(jsonDefaults)) {
            ObjectNode n = node.putObject("json");
            putIfNotNull(n, "recordProperty", json.recordProperty());
            if (!json.encoding().equals(jsonDefaults.encoding())) {
                n.put("encoding", json.encoding().name());
            }
            if (json.firstArray()) {
                n.put("firstArray", true);
            }
        }
        return node;
    }

    private static ObjectNode field(FieldDefinition field) {
        FieldDefinition defaults = FieldDefinition.builder(field.name()).build();
        ObjectNode node = NODES.objectNode();
        putIfNotNull(node, "name", field.name());
        putIfNotNull(node, "column", field.column());
        if (field.inInput() != defaults.inInput()) {
            node.put("inInput", field.inInput());
        }
        if (field.optional() != defaults.optional()) {
            node.put("optional", field.optional());
        }
        putIfNotNull(node, "copyFrom", field.copyFrom());
        if (field.copyMode() != null) {
            node.put("copyMode", field.copyMode().name());
        }
        if (!field.textTransforms().isEmpty()) {
            ArrayNode transforms = node.putArray("textTransforms");
            field.textTransforms().forEach(transform -> transforms.add(textTransform(transform)));
        }
        if (!field.translations().isEmpty()) {
            ArrayNode translations = node.putArray("translations");
            field.translations().forEach(translation -> translations.add(translation(translation)));
        }
        putIfNotDefault(node, "onUnmatched", field.onUnmatched(), defaults.onUnmatched());
        putIfNotNull(node, "transformer", field.transformer());
        putIfNotDefault(node, "type", field.type(), defaults.type());
        if (field.typeFormat() != null) {
            node.set("typeFormat", typeFormat(field.typeFormat()));
        }
        if (!field.validations().equals(Validations.NONE)) {
            node.set("validations", validations(field.validations()));
        }
        OnEmpty derived = field.defaultValue() != null ? OnEmpty.DEFAULT : OnEmpty.EMPTY;
        putIfNotDefault(node, "onEmpty", field.onEmpty(), derived);
        putIfNotNull(node, "defaultValue", field.defaultValue());
        if (field.defaultMode() != null) {
            node.put("defaultMode", field.defaultMode().name());
        }
        putIfNotNull(node, "expression", field.expression());
        if (field.outputAs() != null) {
            node.put("outputAs", field.outputAs().name());
        }
        return node;
    }

    private static JsonNode textTransform(TextTransform transform) {
        return switch (transform) {
            case TextTransform.Upper ignored -> NODES.stringNode("UPPER");
            case TextTransform.Lower ignored -> NODES.stringNode("LOWER");
            case TextTransform.StripAccents ignored -> NODES.stringNode("STRIP_ACCENTS");
            case TextTransform.PadLeft(int length, char padChar) -> pad("PAD_LEFT", length, padChar);
            case TextTransform.PadRight(int length, char padChar) -> pad("PAD_RIGHT", length, padChar);
        };
    }

    private static ObjectNode pad(String type, int length, char padChar) {
        ObjectNode node = NODES.objectNode();
        node.put("type", type);
        node.put("length", length);
        node.put("char", String.valueOf(padChar));
        return node;
    }

    private static ObjectNode translation(Translation translation) {
        ObjectNode node = NODES.objectNode();
        putIfNotNull(node, "from", translation.from());
        if (translation.to() == null) {
            node.putNull("to");
        } else {
            node.put("to", translation.to());
        }
        putIfNotDefault(node, "direction", translation.direction(), TranslationDirection.BOTH);
        return node;
    }

    private static JsonNode typeFormat(TypeFormat format) {
        return switch (format) {
            case TypeFormat.DateFormat(String pattern, Locale locale) when locale == null ->
                    NODES.stringNode(pattern);
            case TypeFormat.DateFormat(String pattern, Locale locale) -> {
                ObjectNode node = NODES.objectNode();
                node.put("pattern", pattern);
                node.put("locale", locale.toLanguageTag());
                yield node;
            }
            case TypeFormat.DecimalFormat(Character decimal, Character grouping) -> {
                ObjectNode node = NODES.objectNode();
                if (decimal != null) {
                    node.put("decimalSeparator", String.valueOf(decimal));
                }
                if (grouping != null) {
                    node.put("groupingSeparator", String.valueOf(grouping));
                }
                yield node;
            }
            case TypeFormat.BooleanFormat(String trueLiteral, String falseLiteral) -> {
                ObjectNode node = NODES.objectNode();
                node.put("trueLiteral", trueLiteral);
                node.put("falseLiteral", falseLiteral);
                yield node;
            }
        };
    }

    private static ObjectNode validations(Validations validations) {
        ObjectNode node = NODES.objectNode();
        if (validations.minLength() != null) {
            node.put("minLength", validations.minLength());
        }
        if (validations.maxLength() != null) {
            node.put("maxLength", validations.maxLength());
        }
        putIfNotNull(node, "regex", validations.regex());
        if (validations.allowedValues() != null) {
            ArrayNode values = node.putArray("allowedValues");
            validations.allowedValues().forEach(values::add);
        }
        return node;
    }

    // ---------------------------------------------------------------- channels and actions

    private static JsonNode channel(ChannelDefinition channel) {
        return switch (channel) {
            case ProvidedStreamChannel ignored -> NODES.stringNode("PROVIDED_STREAM");
            case LocalDirectoryChannel local -> {
                LocalDirectoryChannel defaults = new LocalDirectoryChannel(local.directory(), null, null);
                ObjectNode node = NODES.objectNode();
                node.put("type", "LOCAL_DIRECTORY");
                if (local.directory() != null) {
                    node.put("directory", local.directory().toString());
                }
                if (local.maxFileSize() != null) {
                    node.put("maxFileSize", local.maxFileSize());
                }
                putIfNotDefault(node, "onExistingFile", local.onExistingFile(), defaults.onExistingFile());
                yield node;
            }
            case SftpChannel sftp -> sftp(sftp);
        };
    }

    private static ObjectNode sftp(SftpChannel sftp) {
        ObjectNode node = NODES.objectNode();
        node.put("type", "SFTP");
        putIfNotNull(node, "host", sftp.host());
        if (sftp.port() != SftpChannel.DEFAULT_PORT) {
            node.put("port", sftp.port());
        }
        putIfNotNull(node, "username", sftp.username());
        putIfNotNull(node, "password", credential(sftp.password()));
        putIfNotNull(node, "privateKey", credential(sftp.privateKey()));
        if (sftp.privateKeyFile() != null) {
            node.put("privateKeyFile", sftp.privateKeyFile().toString());
        }
        putIfNotNull(node, "privateKeyPassphrase", credential(sftp.privateKeyPassphrase()));
        putIfNotNull(node, "remoteDirectory", sftp.remoteDirectory());
        if (sftp.knownHostsFile() != null) {
            node.put("knownHostsFile", sftp.knownHostsFile().toString());
        }
        putIfNotNull(node, "hostKeyFingerprint", sftp.hostKeyFingerprint());
        if (sftp.insecureAcceptAnyHostKey()) {
            node.put("insecureAcceptAnyHostKey", true);
        }
        if (!sftp.timeout().equals(SftpChannel.DEFAULT_TIMEOUT)) {
            node.put("timeout", sftp.timeout().toString());
        }
        if (sftp.maxFileSize() != null) {
            node.put("maxFileSize", sftp.maxFileSize());
        }
        putIfNotDefault(node, "onExistingFile", sftp.onExistingFile(), sftpDefaults().onExistingFile());
        return node;
    }

    /** A valid {@code ${secret:name}} reference as is; any other credential masked (INV-10). */
    private static String credential(String value) {
        if (value == null) {
            return null;
        }
        return SecretReference.name(value).isPresent() ? value : DefinitionValidator.MASKED;
    }

    private static JsonNode postSuccessAction(PostSuccessAction action) {
        return switch (action) {
            case PostSuccessAction.None ignored -> NODES.stringNode("NONE");
            case PostSuccessAction.Delete ignored -> NODES.stringNode("DELETE");
            case PostSuccessAction.Move(String remoteFolder) -> {
                ObjectNode node = NODES.objectNode();
                node.put("type", "MOVE");
                node.put("remoteFolder", remoteFolder);
                yield node;
            }
        };
    }

    // ---------------------------------------------------------------- helpers

    private static IntegrationDefinition defaults() {
        return IntegrationDefinition.builder().build();
    }

    private static SftpChannel sftpDefaults() {
        return new SftpChannel(null, SftpChannel.DEFAULT_PORT, null, null, null, null, null, null, null, null, false,
                null, null, null);
    }

    private static void putIfNotNull(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static void putIfNotDefault(ObjectNode node, String name, Enum<?> value, Enum<?> defaultValue) {
        if (value != null && value != defaultValue) {
            node.put(name, value.name());
        }
    }
}
