package org.tie.fixtures;

import java.nio.file.Path;
import org.tie.config.ColumnLocation;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.config.LocalDirectoryChannel;
import org.tie.config.SftpChannel;

/** Valid baseline definitions; tests change one thing and check the resulting problems. */
public final class Definitions {

    public static final Path IN = Path.of("/data/in");
    public static final Path OUT = Path.of("/data/out");

    private Definitions() {
    }

    public static Layout.Builder csvLayout(String name) {
        return Layout.builder(name)
                .fileNamePattern(name + "_{timestamp:yyyyMMddHHmmss}")
                .allowedFormats(FileFormat.CSV)
                .columnLocation(ColumnLocation.HEADER)
                .field(FieldDefinition.builder("nif"))
                .field(FieldDefinition.builder("sexo"));
    }

    public static IntegrationDefinition.Builder validImport() {
        return IntegrationDefinition.importing("altas")
                .layout(csvLayout("alta"))
                .inputChannel(LocalDirectoryChannel.of(IN));
    }

    public static IntegrationDefinition.Builder validExport() {
        return IntegrationDefinition.exporting("salida")
                .layout(Layout.builder("out")
                        .allowedFormats(FileFormat.CSV)
                        .field(FieldDefinition.builder("nif")))
                .outputChannel(LocalDirectoryChannel.of(OUT))
                .outputNamePattern("out_{timestamp:yyyyMMddHHmmss}");
    }

    /** Import of JSON files with a nested child layout. */
    public static IntegrationDefinition.Builder validNestedImport() {
        return IntegrationDefinition.importing("pedidos")
                .layout(Layout.builder("order")
                        .fileNamePattern("orders_{timestamp:yyyyMMdd}")
                        .allowedFormats(FileFormat.JSON)
                        .json(new JsonOptions("orders", null))
                        .field(FieldDefinition.builder("id")))
                .layout(Layout.builder("line")
                        .parent("order", "lines")
                        .field(FieldDefinition.builder("sku")))
                .inputChannel(LocalDirectoryChannel.of(IN));
    }

    public static SftpChannel.Builder validSftp() {
        return SftpChannel.builder()
                .host("sftp.example.com")
                .username("tie")
                .password("s3cr3t")
                .remoteDirectory("/in")
                .knownHostsFile(Path.of("/etc/tie/known_hosts"));
    }
}
