package dev.mdz.wolpi.extension.mapping;

import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;

/// Exposes Java Records as read-only proxy objects to GraalVM Polyglot contexts to
/// allow for idiomatic access without getter invocations.
public class RecordProxy implements ProxyObject {
    private final Language guestLanguage;
    private final Record record;
    private final Map<String, Method> getters;

    public RecordProxy(Record record, Language guestLanguage) {
        this.guestLanguage = guestLanguage;
        this.record = record;
        this.getters = Arrays.stream(record.getClass().getRecordComponents())
                .collect(java.util.stream.Collectors.toMap(RecordComponent::getName, RecordComponent::getAccessor));
    }

    public Record getRecord() {
        return record;
    }

    @Override
    public @Nullable Object getMember(String key) {
        var getter = getters.get(key);
        if (getter == null) {
            return null;
        }
        try {
            var value = MethodHandles.lookup().unreflect(getter).bindTo(record).invoke();
            return PolyglotHelpers.toGuest(value, guestLanguage);
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public Object getMemberKeys() {
        return ProxyArray.fromArray(getters.keySet().toArray());
    }

    @Override
    public boolean hasMember(String key) {
        return getters.containsKey(key);
    }

    @Override
    public void putMember(String key, Value value) {
        throw new UnsupportedOperationException(
                "RecordProxy(%s) is immutable.".formatted(record.getClass().getName()));
    }
}
