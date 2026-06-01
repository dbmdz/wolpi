package dev.mdz.wolpi.extension.mapping;

import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

/// Responsible for mapping polyglot [Value]s to Java [Record]s of a specific type.
///
/// The polyglot values can either be hash-like objects (e.g. a JavaScript `object` or a
/// Python `dict`) or values with member fields (e.g. a Python object with attributes).
public class RecordValueMapper<T extends Record> {
    private final Class<T> cls;
    private final List<String> requiredMembers;

    public RecordValueMapper(Class<T> cls) {
        this.cls = cls;
        this.requiredMembers = Arrays.stream(cls.getRecordComponents())
                .filter(c -> !c.getAnnotatedType().isAnnotationPresent(Nullable.class))
                .map(RecordComponent::getName)
                .toList();
    }

    /// Checks if the given polyglot [Value] can be converted to the target record type.
    ///
    /// @param value The polyglot value to check.
    public boolean accepts(Value value) {
        return requiredMembers.isEmpty()
                || requiredMembers.stream().allMatch(m -> PolyglotHelpers.hasDictOrObjectMember(m, value, true));
    }

    /// Converts the given polyglot [Value] to an instance of the target record type.
    ///
    /// @param value The polyglot value to convert.
    /// @throws IllegalArgumentException if the value is missing a non-nullable member.
    /// @throws RuntimeException if the record instance could not be created for any other
    ///                          reason.
    public T convert(Value value) {
        var members = cls.getRecordComponents();
        var params = Arrays.stream(cls.getRecordComponents())
                .map(member -> {
                    var graalMember = PolyglotHelpers.getDictOrObjectMember(member.getName(), value, true);
                    if (graalMember == null || graalMember.isNull()) {
                        if (!member.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                            throw new IllegalArgumentException(
                                    "Missing non-nullable member %s while creating record instance of %s from polyglot value [%s]"
                                            .formatted(member.getName(), cls.getName(), value.toString()));
                        }
                        return null;
                    } else {
                        return convertMember(graalMember, member);
                    }
                })
                .toArray(Object[]::new);
        var memberTypes = Arrays.stream(members).map(RecordComponent::getType).toArray(Class[]::new);
        try {
            return cls.getDeclaredConstructor(memberTypes).newInstance(params);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create record instance of %s of the polyglot value [%s]"
                            .formatted(cls.getName(), value.toString()),
                    e);
        }
    }

    /// Convert a single polyglot [Value] to the expected member type.
    ///
    /// For [List] members whose generic type is a parameterized [List]<T>, each
    /// array element is converted individually via [Value.as]. This avoids
    /// type-erasure: a plain `value.as(List.class)` would produce a raw list
    /// of polyglot proxy objects that later cause a [ClassCastException] when
    /// downstream code iterates expecting the declared element type (e.g.
    /// `ImageInfo.sizes()` iterating `ImageSize`).
    ///
    /// Note: [Map] fields are currently all nullable and handled by the general
    /// [Value.as] fallback. If a non-nullable [Map] field is ever added, that
    /// path will likewise need per-entry conversion.
    ///
    /// @see graalContextSupplier.buildHostAccess the [RecordValueMapper] registration
    ///      that enables `element.as(elementClass)` to re-enter record conversion
    private static @Nullable Object convertMember(Value value, RecordComponent member) {
        Type genericType = member.getGenericType();
        if (genericType instanceof ParameterizedType pt
                && member.getType() == List.class
                && pt.getActualTypeArguments()[0] instanceof Class<?> elementClass) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                Value element = value.getArrayElement(i);
                list.add(element.isNull() ? null : element.as(elementClass));
            }
            return list;
        }
        return value.as(member.getType());
    }
}
