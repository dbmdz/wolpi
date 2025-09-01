package dev.mdz.iiif.wolpi.util;

import java.util.Set;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

/// Helper functions for working with GraalVM Polyglot Values.
///
/// Currently only used to provide a unifying API for acessing members of objects and
/// dictionary-like values, to make it easier for extensions.
public class PolyglotHelpers {

  /// Check if a Value has either a dictionary entry or an object member with the given name
  ///
  /// @param name   The name of the member or dictionary entry to check for
  /// @param source The Value to check, should be either an object or a dictionary-like value
  /// @return if the Value has either a dictionary entry or an object member with the
  public static boolean hasDictOrObjectMember(String name, Value source) {
    return (source.hasHashEntries() && source.hasHashEntry(name)) || source.hasMember(name);
  }

  /// Get a Value's dictionary entry or object member with the given name, or null if neither
  /// exists
  ///
  /// @param name   The name of the member or dictionary entry to get
  /// @param source The Value to get from, should be either an object or a dictionary-like value
  /// @return The value of the member or dictionary entry, or [null] if neither exists
  public static @Nullable Value getDictOrObjectMember(String name, Value source) {
    if (source.hasHashEntries() && source.hasHashEntry(name)) {
      return source.getHashValue(name);
    } else if (source.hasMember(name)) {
      return source.getMember(name);
    } else {
      return null;
    }
  }

  /// Iterate over the keys of a Value, which can be either a dictionary-like value, an object or
  /// simply the bindings within a given scope.
  ///
  /// @param source The [Value] to get the keys from
  /// @return A set of the keys in the [Value]
  public static Set<String> dictOrMemberKeys(Value source) {
    if (source.hasHashEntries()) {
      var it = source.getHashKeysIterator();
      Set<String> keys = new java.util.HashSet<>();
      while (it.hasIteratorNextElement()) {
        keys.add(it.getIteratorNextElement().asString());
      }
      return keys;
    } else if (source.hasMembers()) {
      return source.getMemberKeys();
    } else {
      throw new IllegalArgumentException(
          "Polyglot Value is not a hash/dict and does not support members!");
    }
  }
}
