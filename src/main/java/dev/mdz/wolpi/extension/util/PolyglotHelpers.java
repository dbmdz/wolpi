package dev.mdz.wolpi.extension.util;

import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import dev.mdz.wolpi.extension.model.Language;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyHashMap;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;

/// Helper functions for working with GraalVM Polyglot Values.
///
/// Currently only used to provide a unifying API for acessing members of objects and
/// dictionary-like values, to make it easier for extensions.
public class PolyglotHelpers {
    ///  Converter to convert camelCase to snake_case
    private static final Converter<String, String> camelToSnake =
            CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.LOWER_UNDERSCORE);

    /// Check if a Value has either a dictionary entry or an object member with the given name
    ///
    /// @param name   The name of the member or dictionary entry to check for
    /// @param source The Value to check, should be either an object or a dictionary-like value
    /// @return if the Value has either a dictionary entry or an object member with the
    public static boolean hasDictOrObjectMember(String name, Value source) {
        return (source.hasHashEntries() && source.hasHashEntry(name)) || source.hasMember(name);
    }

    /// Like [#hasDictOrObjectMember], but allows for flexible casing by also checking for the
    /// snake_case version of the name if the original name is not found.
    ///
    /// @param name            The name of the member or dictionary entry to check for, in camelCase
    /// @param source          The Value to check, should be either an object or a dictionary-like
    ///                        value
    /// @param flexibleCasing  If true, also checks for the snake_case version of the name if the
    ///                        original (assumed camelCase) name is not found
    /// @return if the Value has either a dictionary entry or an object member with the
    ///         given name or its snake_case variant (if `flexibleCasing` is true)
    public static boolean hasDictOrObjectMember(String name, Value source, boolean flexibleCasing) {
        var has = hasDictOrObjectMember(name, source);
        if (!has && flexibleCasing) {
            has = hasDictOrObjectMember(camelToSnake.convert(name), source);
        }
        return has;
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

    /// Like [#getDictOrObjectMember], but allows for flexible casing by also checking for the
    /// snake_case version of the name if the original name is not found.
    ///
    /// @param name            The name of the member or dictionary entry to get, in camelCase
    /// @param source          The Value to get from, should be either an object or a
    ///                        dictionary-like value
    /// @param flexibleCasing  If true, also checks for the snake_case version of the name if the
    ///                        original (assumed camelCase) name is not found
    public static @Nullable Value getDictOrObjectMember(String name, Value source, boolean flexibleCasing) {
        var val = getDictOrObjectMember(name, source);
        if (val == null && flexibleCasing) {
            val = getDictOrObjectMember(camelToSnake.convert(name), source);
        }
        return val;
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
            throw new IllegalArgumentException("Polyglot Value is not a hash/dict and does not support members!");
        }
    }

    /// Convert value to a Polyglot guest object, converting `Map<?, ?>` values to [ProxyObject]s
    ///
    /// This method is mainly intended to pass Java [Map] and [List] values with [Map] members to
    /// the guest in a way that is easy to consume from JavaScript or Python.
    ///
    /// If `obj` is a `Map`, it is converted to a `ProxyObject` with all keys converted to `String`s
    /// and all values recursively converted using this method.
    ///
    /// If `obj` is a `List`, all elements are recursively converted using this method.
    ///
    /// All other values are returned as-is.
    public static @Nullable Object toGuest(@Nullable Object obj, Language lang) {
        if (obj == null) {
            return obj;
        }
        return switch (obj) {
            case Map<?, ?> map -> {
                Map<String, Object> guestMap = new HashMap<>();
                for (var entry : map.entrySet()) {
                    guestMap.put(entry.getKey().toString(), toGuest(entry.getValue(), lang));
                }
                yield switch (lang) {
                    case JAVASCRIPT -> ProxyObject.fromMap(guestMap);
                    case PYTHON -> ProxyHashMap.from(Collections.unmodifiableMap(guestMap));
                };
            }
            case List<?> list -> list.stream().map(v -> toGuest(v, lang)).toList();
            default -> obj;
        };
    }
}
