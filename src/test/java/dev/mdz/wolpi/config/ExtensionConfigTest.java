package dev.mdz.wolpi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ExtensionConfigTest {
    @Test
    public void testConfigFixup() {
        var incorrectlyMappedConfig = Map.of(
                "listAsMap",
                        Map.of(
                                "0", "first",
                                "1", "second",
                                "2",
                                        Map.of(
                                                "nestedListAsMap",
                                                Map.of(
                                                        "0", "nestedFirst",
                                                        "1", "nestedSecond"))),
                "regularMap",
                        Map.of(
                                "key1", "value1",
                                "key2", "value2"),
                "regularMapWithNumericalGaps", Map.of("3", "foobar"),
                "regularKey", "regularValue");
        var config = new ExtensionConfig(null, null, null, incorrectlyMappedConfig, false);
        assertThat(config.config())
                .isNotNull()
                .containsEntry("regularMap", Map.of("key1", "value1", "key2", "value2"))
                .containsEntry("regularMapWithNumericalGaps", Map.of("3", "foobar"))
                .containsEntry("regularKey", "regularValue")
                .containsEntry(
                        "listAsMap",
                        List.of("first", "second", Map.of("nestedListAsMap", List.of("nestedFirst", "nestedSecond"))));
    }
}
