package dev.mdz.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ESMFileSystem")
class ESMFileSystemTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldResolveWolpiModulesFromNestedJarLocation() throws IOException {
        Path jarPath = tempDir.resolve("wolpi-test.jar");
        URI jarUri = URI.create("jar:" + jarPath.toUri());
        try (var jarFs = FileSystems.newFileSystem(jarUri, Map.of("create", "true"))) {
            Path modulePath = jarFs.getPath("/BOOT-INF/classes/js/fetch.mjs");
            Files.createDirectories(modulePath.getParent());
            Files.writeString(modulePath, "export default 'fetch from nested jar';");
        }
        var fs = new ESMFileSystem(
                "jar:nested:%s/!BOOT-INF/classes!/dev/mdz/wolpi/extension/ESMFileSystem.class".formatted(jarPath));

        Path fetchModulePath = fs.parsePath("wolpi:fetch");

        assertThat(fetchModulePath).isNotNull();
        try (var chan = fs.newByteChannel(fetchModulePath, Set.of(StandardOpenOption.READ))) {
            assertThat(chan.size()).isGreaterThan(0);
        }
    }
}
