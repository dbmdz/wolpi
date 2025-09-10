package dev.mdz.wolpi.extension.util;

import java.lang.reflect.RecordComponent;
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
    this.requiredMembers =
        Arrays.stream(cls.getRecordComponents())
            .filter(c -> !c.getAnnotatedType().isAnnotationPresent(Nullable.class))
            .map(RecordComponent::getName)
            .toList();
  }

  /// Checks if the given polyglot [Value] can be converted to the target record type.
  ///
  /// @param value The polyglot value to check.
  public boolean accepts(Value value) {
    return requiredMembers.stream().allMatch(m -> PolyglotHelpers.hasDictOrObjectMember(m, value));
  }

  /// Converts the given polyglot [Value] to an instance of the target record type.
  ///
  /// @param value The polyglot value to convert.
  /// @throws IllegalArgumentException if the value is missing a non-nullable member.
  /// @throws RuntimeException if the record instance could not be created for any other
  ///                          reason.
  public T convert(Value value) {
    var members = cls.getRecordComponents();
    var params =
        Arrays.stream(cls.getRecordComponents())
            .map(
                member -> {
                  var graalMember = PolyglotHelpers.getDictOrObjectMember(member.getName(), value);
                  if (graalMember == null || graalMember.isNull()) {
                    if (!member.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                      throw new IllegalArgumentException(
                          "Missing non-nullable member %s while creating record instance of %s from polyglot value [%s]"
                              .formatted(member.getName(), cls.getName(), value.toString()));
                    }
                    return null;
                  } else {
                    return graalMember.as(member.getType());
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
}
