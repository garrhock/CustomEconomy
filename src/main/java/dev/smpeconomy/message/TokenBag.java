package dev.smpeconomy.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Values substituted into a message's {tokens}. Names are explicit so they can be checked
 * against the key's declaration — varargs pairs let a typo'd token reach players as literal
 * {price}.
 */
public final class TokenBag {

    private static final TokenBag EMPTY = new TokenBag(Map.of());

    private final Map<String, String> values;

    private TokenBag(Map<String, String> values) {
        this.values = values;
    }

    public static TokenBag of() {
        return new TokenBag(new LinkedHashMap<>());
    }

    public static TokenBag empty() {
        return EMPTY;
    }

    public TokenBag put(String name, Object value) {
        if (values == Map.<String, String>of()) {
            throw new UnsupportedOperationException("TokenBag.empty() is immutable; use TokenBag.of()");
        }
        values.put(name, String.valueOf(value));
        return this;
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    /** Unknown placeholders are left alone; the validator is what catches those. */
    public String applyTo(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
