package org.tie;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Runs after {@code package}: tie-core is published without module-info (POI depends on a
 * filename-based automodule), so the jar must carry a stable automatic module name.
 */
class PackagingIT {

    @Test
    void jarDeclaresAutomaticModuleName() throws IOException {
        Path jar;
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            jar = files.filter(path -> path.getFileName().toString().matches("tie-core-.*(?<!-sources|-javadoc)\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("tie-core jar not found in target/"));
        }
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            assertThat(jarFile.getManifest().getMainAttributes().getValue("Automatic-Module-Name"))
                    .isEqualTo("org.tie");
        }
    }
}
