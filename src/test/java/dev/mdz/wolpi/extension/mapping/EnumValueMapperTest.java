package dev.mdz.wolpi.extension.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mdz.wolpi.extension.model.ExtensionHooks;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnumValueMapper")
public class EnumValueMapperTest {
    @Test
    @DisplayName("should map enum constant names case-insensitively via Value.as")
    void shouldMapEnumConstantNamesCaseInsensitivelyViaValueAs() {
        var hostAccessBuilder = HostAccess.newBuilder(HostAccess.ALL);
        var mapper = new EnumValueMapper<>(ExtensionHooks.class);
        hostAccessBuilder.targetTypeMapping(Value.class, ExtensionHooks.class, mapper::accepts, mapper::convert);
        try (var ctx = Context.newBuilder("js")
                .allowHostAccess(hostAccessBuilder.build())
                .build()) {
            assertThat(ctx.eval("js", "'SCALE'").as(ExtensionHooks.class)).isEqualTo(ExtensionHooks.SCALE);
            assertThat(ctx.eval("js", "'scale'").as(ExtensionHooks.class)).isEqualTo(ExtensionHooks.SCALE);
        }
    }

    @Test
    @DisplayName("should reject invalid enum names")
    void shouldRejectInvalidEnumNames() {
        var mapper = new EnumValueMapper<>(ExtensionHooks.class);
        try (var ctx = Context.create("js")) {
            assertThatThrownBy(() -> mapper.convert(ctx.eval("js", "'not-a-hook'")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid enum name");
        }
    }
}
