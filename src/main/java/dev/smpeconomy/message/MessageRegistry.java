package dev.smpeconomy.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every key this build knows about. Modules register at startup; validation needs the union
 * to spot both missing keys and stale ones.
 */
public final class MessageRegistry {

    private final Map<String, MessageKey> byPath = new LinkedHashMap<>();

    public void register(MessageKey... keys) {
        for (MessageKey key : keys) {
            MessageKey previous = byPath.put(key.path(), key);
            if (previous != null && previous != key) {
                throw new IllegalStateException(
                        "Two message keys claim the path " + key.path() + ": "
                        + previous + " and " + key);
            }
        }
    }

    public List<MessageKey> all() {
        return Collections.unmodifiableList(new ArrayList<>(byPath.values()));
    }

    public boolean knows(String path) {
        return byPath.containsKey(path);
    }

    public int size() {
        return byPath.size();
    }
}
