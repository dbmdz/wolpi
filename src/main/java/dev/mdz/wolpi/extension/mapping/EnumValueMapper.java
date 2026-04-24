package dev.mdz.wolpi.extension.mapping;

import dev.mdz.wolpi.extension.model.ExtensionHooks;
import java.util.Arrays;
import org.graalvm.polyglot.Value;

/// Responsible for mapping polyglot [Value]s to Java [Enum] values of a specific type.
///
/// This accepts host enum values directly and otherwise maps string values matching the enum
/// constant name in a case-insensitive way.
public class EnumValueMapper<T extends Enum<T>> {
    private final Class<T> cls;

    public EnumValueMapper(Class<T> cls) {
        this.cls = cls;
    }

    public boolean accepts(Value value) {
        return (value.isHostObject() && cls.isInstance(value.asHostObject())) || value.isString();
    }

    public T convert(Value value) {
        if (value.isHostObject() && cls.isInstance(value.asHostObject())) {
            return cls.cast(value.asHostObject());
        }
        if (value.isString()) {
            String enumName = value.asString();

            if (cls == ExtensionHooks.class) {
                var mapped = ExtensionHooks.fromName(enumName);
                if (mapped == null) {
                    throw new IllegalArgumentException("Invalid extension hook name: '%s'".formatted(enumName));
                }
                //noinspection unchecked
                return (T) mapped;
            }
            return Arrays.stream(cls.getEnumConstants())
                    .filter(c -> c.name().equalsIgnoreCase(enumName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Invalid enum name '%s' for %s".formatted(enumName, cls.getName())));
        }
        throw new ClassCastException("Cannot convert polyglot value [%s] to %s".formatted(value, cls.getName()));
    }
}
