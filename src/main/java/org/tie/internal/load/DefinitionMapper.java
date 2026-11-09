package org.tie.internal.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.tie.config.ChannelDefinition;
import org.tie.config.ColumnLocation;
import org.tie.config.ConsumerErrorPolicy;
import org.tie.config.CopyMode;
import org.tie.config.CsvOptions;
import org.tie.config.DeliveryStrategy;
import org.tie.config.Direction;
import org.tie.config.DuplicateProtection;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.FormatOptions;
import org.tie.config.IntegrationDefinition;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.OnEmpty;
import org.tie.config.OnExistingFile;
import org.tie.config.OnParentRejected;
import org.tie.config.OnUnmatched;
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
import org.tie.config.XlsxOptions;
import org.tie.config.XmlOptions;
import tools.jackson.databind.JsonNode;

/**
 * Maps a parsed JSON/YAML document (§5.5) to an {@link IntegrationDefinition}. Polymorphic values
 * are objects with a {@code type} discriminator; values without parameters may be plain strings.
 */
public final class DefinitionMapper {

    public static final int SUPPORTED_VERSION = 1;

    private final List<String> problems = new ArrayList<>();

    private DefinitionMapper() {
    }

    /** The mapped definition (null if the document has no usable root) and every mapping problem. */
    public record Result(IntegrationDefinition definition, List<String> problems) {
    }

    public static Result map(JsonNode root) {
        DefinitionMapper mapper = new DefinitionMapper();
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new Result(null, List.of("document is empty"));
        }
        if (!root.isObject()) {
            return new Result(null, List.of("document must be an object, got " + Node.describe(root)));
        }
        IntegrationDefinition definition = mapper.definition(new Node(root, "", mapper.problems));
        return new Result(definition, List.copyOf(mapper.problems));
    }

    private IntegrationDefinition definition(Node doc) {
        if (!doc.has("version")) {
            problems.add("version is required");
        } else {
            Integer version = doc.integer("version");
            if (version != null && version != SUPPORTED_VERSION) {
                doc.problem("version", "unsupported version " + version + " (expected " + SUPPORTED_VERSION + ")");
            }
        }
        IntegrationDefinition.Builder builder = IntegrationDefinition.builder()
                .id(doc.string("id"))
                .direction(doc.enumValue("direction", Direction.class))
                .outputNamePattern(doc.string("outputNamePattern"))
                .deliveryStrategy(doc.enumValue("deliveryStrategy", DeliveryStrategy.class))
                .consumerErrorPolicy(doc.enumValue("consumerErrorPolicy", ConsumerErrorPolicy.class));
        List<Layout> layouts = doc.list("layouts", item -> item.isObject() ? layout(item.asObject()) : item.error("an object"));
        if (layouts != null) {
            builder.layouts(layouts);
        }
        List<ChannelDefinition> inputs = doc.list("inputChannels", this::channel);
        if (inputs != null) {
            builder.inputChannels(inputs);
        }
        if (doc.has("outputChannel")) {
            builder.outputChannel(channel(item(doc, "outputChannel")));
        }
        Node threshold = doc.object("rejectionThreshold");
        if (threshold != null) {
            builder.rejectionThreshold(new RejectionThreshold(threshold.integer("maxRejected"),
                    threshold.decimal("maxRejectedPercent")));
            threshold.finish();
        }
        Node protection = doc.object("duplicateProtection");
        if (protection != null) {
            PostSuccessAction action = protection.has("postSuccessAction")
                    ? postSuccessAction(item(protection, "postSuccessAction")) : null;
            builder.duplicateProtection(new DuplicateProtection(protection.bool("watermark", false), action));
            protection.finish();
        }
        Node report = doc.object("resultReport");
        if (report != null) {
            ChannelDefinition channel = report.has("channel") ? channel(item(report, "channel")) : null;
            builder.resultReport(new ResultReport(report.enumValue("mode", ReportMode.class),
                    report.enumValue("format", FileFormat.class), channel, report.string("namePattern")));
            report.finish();
        }
        doc.finish();
        return builder.build();
    }

    // ---------------------------------------------------------------- layouts and fields

    private Layout layout(Node node) {
        Layout.Builder builder = Layout.builder(node.string("name"))
                .fileNamePattern(node.string("fileNamePattern"))
                .columnLocation(node.enumValue("columnLocation", ColumnLocation.class))
                .parent(node.string("parent"))
                .nestedKey(node.string("nestedKey"))
                .onParentRejected(node.enumValue("onParentRejected", OnParentRejected.class));
        List<FileFormat> formats = node.list("allowedFormats", item -> item.asEnum(FileFormat.class));
        if (formats != null) {
            if (Set.copyOf(formats).size() != formats.size()) {
                node.problem("allowedFormats", "must not contain duplicates");
            }
            builder.allowedFormats(Set.copyOf(formats));
        }
        Node options = node.object("formatOptions");
        if (options != null) {
            builder.formatOptions(formatOptions(options));
        }
        List<FieldDefinition> fields = node.list("fields", item -> item.isObject() ? field(item.asObject()) : item.error("an object"));
        if (fields != null) {
            builder.fields(fields);
        }
        node.finish();
        return builder.build();
    }

    private FormatOptions formatOptions(Node node) {
        FormatOptions options = FormatOptions.DEFAULTS;
        Node csv = node.object("csv");
        if (csv != null) {
            CsvOptions.Builder builder = CsvOptions.DEFAULTS.toBuilder();
            Character separator = csv.character("separator");
            if (separator != null) {
                builder.separator(separator);
            }
            Character quote = csv.character("quote");
            if (quote != null) {
                builder.quote(quote);
            }
            builder.encoding(csv.charset("encoding"));
            Boolean hasHeader = csv.bool("hasHeader");
            if (hasHeader != null) {
                builder.hasHeader(hasHeader);
            }
            Integer firstDataRow = csv.integer("firstDataRow");
            if (firstDataRow != null) {
                builder.firstDataRow(firstDataRow);
            }
            builder.skipBlankRecords(csv.bool("skipBlankRecords", false));
            csv.finish();
            options = options.withCsv(builder.build());
        }
        Node xlsx = node.object("xlsx");
        if (xlsx != null) {
            Integer firstDataRow = xlsx.integer("firstDataRow");
            options = options.withXlsx(new XlsxOptions(xlsx.string("sheet"),
                    xlsx.bool("hasHeader", XlsxOptions.DEFAULTS.hasHeader()),
                    firstDataRow == null ? XlsxOptions.DEFAULTS.firstDataRow() : firstDataRow));
            xlsx.finish();
        }
        Node xml = node.object("xml");
        if (xml != null) {
            options = options.withXml(new XmlOptions(xml.string("recordElement"), xml.string("rootElement"),
                    xml.charset("encoding")));
            xml.finish();
        }
        Node json = node.object("json");
        if (json != null) {
            options = options.withJson(new JsonOptions(json.string("recordProperty"), json.charset("encoding")));
            json.finish();
        }
        node.finish();
        return options;
    }

    private FieldDefinition field(Node node) {
        FieldDefinition.Builder builder = FieldDefinition.builder(node.string("name"))
                .column(node.string("column"))
                .inInput(node.bool("inInput", true))
                .optional(node.bool("optional", false))
                .copyFrom(node.string("copyFrom"))
                .copyMode(node.enumValue("copyMode", CopyMode.class))
                .onUnmatched(node.enumValue("onUnmatched", OnUnmatched.class))
                .transformer(node.string("transformer"))
                .type(node.enumValue("type", FieldType.class))
                .onEmpty(node.enumValue("onEmpty", OnEmpty.class))
                .defaultValue(node.string("defaultValue"))
                .outputAs(node.enumValue("outputAs", OutputAs.class));
        List<TextTransform> transforms = node.list("textTransforms", this::textTransform);
        if (transforms != null) {
            builder.textTransforms(transforms);
        }
        List<Translation> translations = node.list("translations",
                item -> item.isObject() ? translation(item.asObject()) : item.error("an object"));
        if (translations != null) {
            builder.translations(translations);
        }
        if (node.has("typeFormat")) {
            builder.typeFormat(typeFormat(item(node, "typeFormat")));
        }
        Node validations = node.object("validations");
        if (validations != null) {
            builder.validations(new Validations(validations.integer("minLength"), validations.integer("maxLength"),
                    validations.string("regex"), validations.list("allowedValues", Node.Item::asString)));
            validations.finish();
        }
        node.finish();
        return builder.build();
    }

    private TextTransform textTransform(Node.Item item) {
        String type;
        Node node = null;
        if (item.isString()) {
            type = item.stringValue();
        } else if (item.isObject()) {
            node = item.asObject();
            type = node.string("type");
            if (type == null) {
                node.problem("type", "is required");
                return null;
            }
        } else {
            return item.error("a text transform name or object");
        }
        TextTransform transform = switch (type) {
            case "UPPER" -> TextTransform.UPPER;
            case "LOWER" -> TextTransform.LOWER;
            case "STRIP_ACCENTS" -> TextTransform.STRIP_ACCENTS;
            case "PAD_LEFT", "PAD_RIGHT" -> pad(item, node, type);
            default -> item.error("one of UPPER, LOWER, STRIP_ACCENTS, PAD_LEFT, PAD_RIGHT");
        };
        if (node != null) {
            node.finish();
        }
        return transform;
    }

    private TextTransform pad(Node.Item item, Node node, String type) {
        if (node == null) {
            return item.error("an object with length and char for " + type);
        }
        Integer length = required(node, "length", node.integer("length"));
        Character padChar = required(node, "char", node.character("char"));
        if (length == null || padChar == null) {
            return null;
        }
        return type.equals("PAD_LEFT") ? TextTransform.padLeft(length, padChar) : TextTransform.padRight(length, padChar);
    }

    private Translation translation(Node node) {
        Translation translation = new Translation(node.string("from"), node.nullableString("to"),
                node.enumValue("direction", TranslationDirection.class));
        node.finish();
        return translation;
    }

    private TypeFormat typeFormat(Node.Item item) {
        if (item.isString()) {
            return TypeFormat.date(item.stringValue());
        }
        if (!item.isObject()) {
            return item.error("a date pattern, {decimalSeparator, groupingSeparator} or {trueLiteral, falseLiteral}");
        }
        Node node = item.asObject();
        boolean decimal = node.has("decimalSeparator") || node.has("groupingSeparator");
        boolean literals = node.has("trueLiteral") || node.has("falseLiteral");
        if (decimal == literals) {
            return item.error("a date pattern, {decimalSeparator, groupingSeparator} or {trueLiteral, falseLiteral}");
        }
        TypeFormat format = decimal
                ? TypeFormat.decimal(node.character("decimalSeparator"), node.character("groupingSeparator"))
                : TypeFormat.booleanLiterals(required(node, "trueLiteral", node.string("trueLiteral")),
                        required(node, "falseLiteral", node.string("falseLiteral")));
        node.finish();
        return format;
    }

    // ---------------------------------------------------------------- channels and actions

    private ChannelDefinition channel(Node.Item item) {
        if (item.isString()) {
            if (item.stringValue().equals("PROVIDED_STREAM")) {
                return ProvidedStreamChannel.INSTANCE;
            }
            return item.error("a channel object (only PROVIDED_STREAM may be written as a string)");
        }
        if (!item.isObject()) {
            return item.error("a channel object");
        }
        Node node = item.asObject();
        String type = node.string("type");
        ChannelDefinition channel = switch (type) {
            case null -> {
                node.problem("type", "is required (SFTP, LOCAL_DIRECTORY or PROVIDED_STREAM)");
                yield null;
            }
            case "SFTP" -> sftp(node);
            case "LOCAL_DIRECTORY" -> new LocalDirectoryChannel(node.filePath("directory"), node.longValue("maxFileSize"),
                    node.enumValue("onExistingFile", OnExistingFile.class));
            case "PROVIDED_STREAM" -> ProvidedStreamChannel.INSTANCE;
            default -> {
                node.problem("type", "expected one of SFTP, LOCAL_DIRECTORY, PROVIDED_STREAM, got '" + type + "'");
                yield null;
            }
        };
        if (type != null) {
            node.finish();
        }
        return channel;
    }

    private SftpChannel sftp(Node node) {
        SftpChannel.Builder builder = SftpChannel.builder()
                .host(node.string("host"))
                .username(node.string("username"))
                .password(node.string("password"))
                .privateKey(node.string("privateKey"))
                .privateKeyFile(node.filePath("privateKeyFile"))
                .privateKeyPassphrase(node.string("privateKeyPassphrase"))
                .remoteDirectory(node.string("remoteDirectory"))
                .knownHostsFile(node.filePath("knownHostsFile"))
                .hostKeyFingerprint(node.string("hostKeyFingerprint"))
                .insecureAcceptAnyHostKey(node.bool("insecureAcceptAnyHostKey", false))
                .timeout(node.duration("timeout"))
                .maxFileSize(node.longValue("maxFileSize"))
                .onExistingFile(node.enumValue("onExistingFile", OnExistingFile.class));
        Integer port = node.integer("port");
        if (port != null) {
            builder.port(port);
        }
        return builder.build();
    }

    private PostSuccessAction postSuccessAction(Node.Item item) {
        String type;
        Node node = null;
        if (item.isString()) {
            type = item.stringValue();
        } else if (item.isObject()) {
            node = item.asObject();
            type = node.string("type");
            if (type == null) {
                node.problem("type", "is required (NONE, MOVE or DELETE)");
                return null;
            }
        } else {
            return item.error("NONE, DELETE or {type: MOVE, remoteFolder}");
        }
        PostSuccessAction action = switch (type) {
            case "NONE" -> PostSuccessAction.NONE;
            case "DELETE" -> PostSuccessAction.DELETE;
            case "MOVE" -> node == null
                    ? item.error("an object {type: MOVE, remoteFolder} for MOVE")
                    : PostSuccessAction.move(required(node, "remoteFolder", node.string("remoteFolder")));
            default -> item.error("one of NONE, MOVE, DELETE");
        };
        if (node != null) {
            node.finish();
        }
        return action;
    }

    // ---------------------------------------------------------------- helpers

    private Node.Item item(Node parent, String name) {
        return parent.item(name);
    }

    private <T> T required(Node node, String name, T value) {
        if (value == null && !node.has(name)) {
            node.problem(name, "is required");
        }
        return value;
    }
}
