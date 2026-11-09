package org.tie.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tie.fixtures.Definitions;

class ModelTest {

    @Test
    void integrationDefaults() {
        IntegrationDefinition definition = IntegrationDefinition.importing("x").build();

        assertThat(definition.deliveryStrategy()).isEqualTo(DeliveryStrategy.STREAMING);
        assertThat(definition.consumerErrorPolicy()).isEqualTo(ConsumerErrorPolicy.REJECT_LINE);
        assertThat(definition.rejectionThreshold()).isEqualTo(RejectionThreshold.NONE);
        assertThat(definition.duplicateProtection()).isEqualTo(DuplicateProtection.NONE);
        assertThat(definition.duplicateProtection().postSuccessAction()).isEqualTo(PostSuccessAction.NONE);
        assertThat(definition.resultReport().mode()).isEqualTo(ReportMode.NONE);
        assertThat(definition.layouts()).isEmpty();
        assertThat(definition.inputChannels()).isEmpty();
    }

    @Test
    void layoutAndOptionDefaults() {
        Layout layout = Layout.builder("a").build();

        assertThat(layout.columnLocation()).isEqualTo(ColumnLocation.HEADER);
        assertThat(layout.onParentRejected()).isEqualTo(OnParentRejected.CASCADE);
        assertThat(layout.isRoot()).isTrue();
        assertThat(layout.formatOptions().csv()).isEqualTo(new CsvOptions(',', '"', StandardCharsets.UTF_8, true, 2, false));
        assertThat(layout.formatOptions().xlsx()).isEqualTo(new XlsxOptions(null, true, 2, false, null, null));
        assertThat(layout.formatOptions().xml().rootElement()).isEqualTo("records");
        assertThat(layout.formatOptions().json().encoding()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void fieldDefaults() {
        FieldDefinition field = FieldDefinition.builder("a").build();

        assertThat(field.inInput()).isTrue();
        assertThat(field.optional()).isFalse();
        assertThat(field.type()).isEqualTo(FieldType.STRING);
        assertThat(field.onUnmatched()).isEqualTo(OnUnmatched.ERROR);
        assertThat(field.validations()).isEqualTo(Validations.NONE);
        assertThat(field.onEmpty()).isEqualTo(OnEmpty.EMPTY);
        assertThat(Translation.of("M", "Male").direction()).isEqualTo(TranslationDirection.BOTH);
        assertThat(new Translation("M", "Male", null).direction()).isEqualTo(TranslationDirection.BOTH);
    }

    @Test
    void onEmptyIsInferredFromDefaultValue() {
        assertThat(FieldDefinition.builder("a").defaultValue("1").build().onEmpty()).isEqualTo(OnEmpty.DEFAULT);
        assertThat(FieldDefinition.builder("a").defaultValue("1").onEmpty(OnEmpty.KEEP).build().onEmpty())
                .isEqualTo(OnEmpty.KEEP);
    }

    @Test
    void effectiveOutputAs() {
        assertThat(FieldDefinition.builder("a").type(FieldType.INTEGER).build().effectiveOutputAs())
                .isEqualTo(OutputAs.NATIVE);
        assertThat(FieldDefinition.builder("a").textTransform(TextTransform.UPPER).build().effectiveOutputAs())
                .isEqualTo(OutputAs.TEXT);
        assertThat(FieldDefinition.builder("a").transformer("t").build().effectiveOutputAs()).isEqualTo(OutputAs.TEXT);
        assertThat(FieldDefinition.builder("a").translation("M", "Male").build().effectiveOutputAs())
                .isEqualTo(OutputAs.TEXT);
        assertThat(FieldDefinition.builder("a").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy"))
                .build().effectiveOutputAs()).isEqualTo(OutputAs.TEXT);
        assertThat(FieldDefinition.builder("a").outputAs(OutputAs.TEXT).build().effectiveOutputAs())
                .isEqualTo(OutputAs.TEXT);
    }

    @Test
    void channelDefaults() {
        SftpChannel sftp = Definitions.validSftp().build();

        assertThat(sftp.port()).isEqualTo(22);
        assertThat(sftp.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(sftp.onExistingFile()).isEqualTo(OnExistingFile.SUFFIX);
        assertThat(LocalDirectoryChannel.of(Path.of("/x")).onExistingFile()).isEqualTo(OnExistingFile.SUFFIX);
    }

    @Test
    void collectionsAreDefensiveImmutableCopies() {
        List<FieldDefinition> fields = new ArrayList<>(List.of(FieldDefinition.builder("a").build()));
        List<TextTransform> transforms = new ArrayList<>(List.of(TextTransform.UPPER));
        Layout layout = Layout.builder("l").fields(fields).build();
        FieldDefinition field = FieldDefinition.builder("a").textTransforms(transforms).build();

        fields.add(FieldDefinition.builder("b").build());
        transforms.clear();

        assertThat(layout.fields()).hasSize(1);
        assertThat(field.textTransforms()).containsExactly(TextTransform.UPPER);
        assertThatThrownBy(() -> layout.fields().add(FieldDefinition.builder("c").build()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> layout.allowedFormats().add(FileFormat.CSV))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("AC-273 the JSON builder defaults are DEFAULTS and the 0.3.0 values; toBuilder keeps every option")
    void jsonOptionsBuilder() {
        JsonOptions full = JsonOptions.builder().recordProperty("rows").encoding(StandardCharsets.UTF_16).firstArray(true).build();

        assertThat(JsonOptions.builder().build()).isEqualTo(JsonOptions.DEFAULTS)
                .isEqualTo(new JsonOptions(null, StandardCharsets.UTF_8, false));
        assertThat(full.toBuilder().build()).isEqualTo(full);
    }

    @Test
    @DisplayName("AC-251 the XLSX builder defaults are DEFAULTS and the 0.2.0 values; toBuilder keeps every option")
    void xlsxOptionsBuilder() {
        XlsxOptions full = XlsxOptions.builder().sheet("S").hasHeader(false).firstDataRow(4).skipBlankRecords(true)
                .cellValues(XlsxCellValues.FORMATTED).locale(java.util.Locale.forLanguageTag("es-ES")).build();

        assertThat(XlsxOptions.builder().build()).isEqualTo(XlsxOptions.DEFAULTS)
                .isEqualTo(new XlsxOptions(null, true, 2, false, null, null));
        assertThat(XlsxOptions.DEFAULTS.effectiveCellValues()).isEqualTo(XlsxCellValues.CANONICAL);
        assertThat(XlsxOptions.DEFAULTS.effectiveLocale()).isEqualTo(java.util.Locale.ROOT);
        assertThat(full.toBuilder().build()).isEqualTo(full);
    }

    @Test
    @DisplayName("AC-228 copyMode survives toBuilder; undeclared it is null and behaves as TEXT")
    void copyModeRoundTrip() {
        FieldDefinition canonical = FieldDefinition.builder("x").copyFrom("y").copyMode(CopyMode.CANONICAL).build();
        FieldDefinition undeclared = FieldDefinition.builder("x").copyFrom("y").build();

        assertThat(canonical.toBuilder().build()).isEqualTo(canonical);
        assertThat(canonical.effectiveCopyMode()).isEqualTo(CopyMode.CANONICAL);
        assertThat(undeclared.copyMode()).isNull();
        assertThat(undeclared.effectiveCopyMode()).isEqualTo(CopyMode.TEXT);
        assertThat(undeclared).isNotEqualTo(canonical);
    }

    @Test
    void equalDefinitionsAreEqualAndRoundTripThroughTheirBuilders() {
        IntegrationDefinition a = Definitions.validNestedImport().build();
        IntegrationDefinition b = Definitions.validNestedImport().build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toBuilder().build()).isEqualTo(a);
        assertThat(a.layouts().getFirst().toBuilder().build()).isEqualTo(a.layouts().getFirst());
        assertThat(FieldDefinition.builder("x").copyFrom("y").translation("M", "Male").build().toBuilder().build())
                .isEqualTo(FieldDefinition.builder("x").copyFrom("y").translation("M", "Male").build());
        assertThat(Definitions.validSftp().build().toBuilder().build()).isEqualTo(Definitions.validSftp().build());
        assertThat(TextTransform.padLeft(5, '0')).isEqualTo(new TextTransform.PadLeft(5, '0'));
        assertThat(a).isNotEqualTo(a.toBuilder().id("other").build());
    }

    @Test
    void sftpToStringNeverPrintsCredentials() {
        SftpChannel sftp = Definitions.validSftp().password("s3cr3t-pass").privateKeyPassphrase("p4ss-phrase").build();
        SftpChannel withKey = Definitions.validSftp().password(null).privateKey("-----BEGIN KEY-----").build();

        assertThat(sftp.toString()).doesNotContain("s3cr3t-pass", "p4ss-phrase").contains("password=****");
        assertThat(withKey.toString()).doesNotContain("BEGIN KEY").contains("password=null", "privateKey=****");
        assertThat(IntegrationDefinition.importing("x").inputChannel(sftp).build().toString()).doesNotContain("s3cr3t-pass");
    }

    @Test
    void fileFormatExtensions() {
        assertThat(FileFormat.fromExtension("CSV")).contains(FileFormat.CSV);
        assertThat(FileFormat.fromExtension("xlsx")).contains(FileFormat.XLSX);
        assertThat(FileFormat.fromExtension("txt")).isEmpty();
        assertThat(FileFormat.fromExtension(null)).isEmpty();
        assertThat(FileFormat.JSON.isTabular()).isFalse();
        assertThat(FileFormat.XLSX.isTabular()).isTrue();
    }
}
