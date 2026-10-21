package dev.mdz.wolpi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpResolvedImageTest {
    private static final URI URL = URI.create("https://example.com/image");

    @Test
    void existingConstructorDefaultsToUrlAndHeaderIdentity() {
        assertThat(new HttpResolvedImage(URL, null).cacheIdentity()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void explicitIdentityMustNotBeBlank(String identity) {
        assertThatThrownBy(() -> new HttpResolvedImage(URL, null, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheIdentity");
    }
}
