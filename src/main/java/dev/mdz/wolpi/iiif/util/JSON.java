package dev.mdz.wolpi.iiif.util;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Utility class with static methods for conveniently creating JSON objects through Java
/// collections.
/// **Note**: This class uses [ImmutableMap] to preserve the insertion order of keys in objects.
public class JSON {

  private JSON() {
    // Prevent instantiation
  }

  ///  Use the builder pattern to construct a JSON object.
  public static class JSONObjectBuilder {

    private final Builder<String, Object> map;

    public JSONObjectBuilder() {
      this.map = ImmutableMap.builder();
    }

    ///  Add a new entry to the JSON object. Entries with null values are skipped.
    public <V> JSONObjectBuilder kv(String key, @Nullable V value) {
      if (value == null) {
        return this;
      }
      map.put(key, value);
      return this;
    }

    /// Get the built JSON object as an immutable map.
    public Map<String, Object> obj() {
      return map.build();
    }
  }

  public static <V> JSONObjectBuilder obj() {
    return new JSONObjectBuilder();
  }

  public static <V> ImmutableMap.Entry<String, V> kv(String key, V value) {
    return Map.entry(key, value);
  }

  @SafeVarargs
  public static <V> ImmutableMap<String, V> obj(Map.Entry<String, V>... entries) {
    return ImmutableMap.ofEntries(entries);
  }

  @SafeVarargs
  public static <E> List<E> arr(E... elements) {
    return List.of(elements);
  }
}
