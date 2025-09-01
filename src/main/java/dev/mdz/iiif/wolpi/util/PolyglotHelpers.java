package dev.mdz.iiif.wolpi.util;

import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

/// Helper functions for working with GraalVM Polyglot Values.
///
/// Currently only used to provide a unifying API for acessing members of objects and
/// dictionar-like values, to make it easier for extensions.
public class PolyglotHelpers {
  /// Check if a Value has either a dictionary entry or an object member with the given name
  ///
  /// @param name The name of the member or dictionary entry to check for
  /// @param source The Value to check, should be either an object or a dictionary-like value
  /// @return if the Value has either a dictionary entry or an object member with the
  public static boolean hasDictOrObjectMember(String name, Value source) {
    return (source.hasHashEntries() && source.hasHashEntry(name)) || source.hasMember(name);
  }

  ///  Get a Value's dictionary entry or object member with the given name, or null if neither exists
  ///
  /// @param name The name of the member or dictionary entry to get
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
}
