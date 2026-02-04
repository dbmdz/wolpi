package dev.mdz.wolpi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExtensionConfig")
public class ExtensionConfigTest {
    @Test
    @DisplayName("should fix config mapping issues")
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

    @Test
    @DisplayName("IndexAuth should accept valid username and password")
    void indexAuthShouldAcceptValidUsernameAndPassword() {
        var auth = new ExtensionConfig.IndexAuth("user", "pass", null);
        assertThat(auth.username()).isEqualTo("user");
        assertThat(auth.password()).isEqualTo("pass");
        assertThat(auth.token()).isNull();
    }

    @Test
    @DisplayName("IndexAuth should accept valid token")
    void indexAuthShouldAcceptValidToken() {
        var auth = new ExtensionConfig.IndexAuth(null, null, "token123");
        assertThat(auth.username()).isNull();
        assertThat(auth.password()).isNull();
        assertThat(auth.token()).isEqualTo("token123");
    }

    @Test
    @DisplayName("IndexAuth should throw if username provided without password")
    void indexAuthShouldThrowIfUsernameWithoutPassword() {
        assertThatThrownBy(() -> new ExtensionConfig.IndexAuth("user", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Both username and password must be provided");
    }

    @Test
    @DisplayName("IndexAuth should throw if password provided without username")
    void indexAuthShouldThrowIfPasswordWithoutUsername() {
        assertThatThrownBy(() -> new ExtensionConfig.IndexAuth(null, "pass", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Both username and password must be provided");
    }

    @Test
    @DisplayName("IndexAuth should throw if both token and username/password provided")
    void indexAuthShouldThrowIfBothTokenAndCredentials() {
        assertThatThrownBy(() -> new ExtensionConfig.IndexAuth("user", "pass", "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot provide both token and username/password");
    }

    @Test
    @DisplayName("ExtensionConfig should throw if pypi has token auth")
    void extensionConfigShouldThrowIfPypiHasTokenAuth() {
        var pkgSource = new ExtensionConfig.PkgSource(
                "test-pkg",
                "1.0.0",
                URI.create("https://example.com"),
                new ExtensionConfig.IndexAuth(null, null, "token123"));
        assertThatThrownBy(() -> new ExtensionConfig(null, null, pkgSource, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PyPI index authentication only supports username/password");
    }

    @Test
    @DisplayName("PkgSource should accept valid configuration with auth")
    void pkgSourceShouldAcceptValidConfigWithAuth() {
        var auth = new ExtensionConfig.IndexAuth("user", "pass", null);
        var pkgSource = new ExtensionConfig.PkgSource("test-pkg", "1.0.0", URI.create("https://example.com"), auth);
        assertThat(pkgSource.pkg()).isEqualTo("test-pkg");
        assertThat(pkgSource.version()).isEqualTo("1.0.0");
        assertThat(pkgSource.index()).isEqualTo(URI.create("https://example.com"));
        assertThat(pkgSource.indexAuth()).isEqualTo(auth);
    }

    @Test
    @DisplayName("ExtensionConfig should throw if npm unscoped package has indexAuth")
    void extensionConfigShouldThrowIfNpmUnscopedPackageHasIndexAuth() {
        var pkgSource = new ExtensionConfig.PkgSource(
                "unscoped-package",
                "1.0.0",
                URI.create("https://registry.example.com"),
                new ExtensionConfig.IndexAuth("user", "pass", null));
        assertThatThrownBy(() -> new ExtensionConfig(null, pkgSource, null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("npm index authentication is only supported for scoped packages");
    }

    @Test
    @DisplayName("ExtensionConfig should accept npm scoped package with indexAuth")
    void extensionConfigShouldAcceptNpmScopedPackageWithIndexAuth() {
        var pkgSource = new ExtensionConfig.PkgSource(
                "@myorg/scoped-package",
                "1.0.0",
                URI.create("https://registry.example.com"),
                new ExtensionConfig.IndexAuth("user", "pass", null));
        assertThatCode(() -> new ExtensionConfig(null, pkgSource, null, null, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PkgSource should accept valid configuration without auth")
    void pkgSourceShouldAcceptValidConfigWithoutAuth() {
        var pkgSource = new ExtensionConfig.PkgSource("test-pkg", "1.0.0", URI.create("https://example.com"), null);
        assertThat(pkgSource.pkg()).isEqualTo("test-pkg");
        assertThat(pkgSource.version()).isEqualTo("1.0.0");
        assertThat(pkgSource.index()).isEqualTo(URI.create("https://example.com"));
        assertThat(pkgSource.indexAuth()).isNull();
    }
}
