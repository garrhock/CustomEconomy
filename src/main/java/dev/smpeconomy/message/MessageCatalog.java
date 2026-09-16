package dev.smpeconomy.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One locale's messages as plain data. No Bukkit here, so it's testable without a server.
 *
 * Nested maps flatten to dotted paths, except plural blocks — those are one message with
 * several forms, not several messages.
 */
public final class MessageCatalog {

    private final Locale locale;
    private final Map<String, Object> entries;

    private MessageCatalog(Locale locale, Map<String, Object> entries) {
        this.locale = locale;
        this.entries = entries;
    }

    public static MessageCatalog of(Locale locale, Map<String, Object> nested) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", nested, flat);
        return new MessageCatalog(locale, Collections.unmodifiableMap(flat));
    }

    public static MessageCatalog empty(Locale locale) {
        return new MessageCatalog(locale, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> out) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey())
                                           : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                if (isPluralBlock(map)) {
                    Map<String, String> forms = new LinkedHashMap<>();
                    map.forEach((k, v) -> forms.put(String.valueOf(k), String.valueOf(v)));
                    out.put(path, Collections.unmodifiableMap(forms));
                } else {
                    flatten(path, map, out);
                }
            } else if (value instanceof List<?> list) {
                List<String> lines = new ArrayList<>(list.size());
                list.forEach(line -> lines.add(String.valueOf(line)));
                out.put(path, Collections.unmodifiableList(lines));
            } else if (value != null) {
                out.put(path, String.valueOf(value));
            }
        }
    }

    private static boolean isPluralBlock(Map<?, ?> map) {
        if (map.isEmpty()) {
            return false;
        }
        for (Object key : map.keySet()) {
            if (!PluralRules.isCategory(String.valueOf(key))) {
                return false;
            }
        }
        return true;
    }

    public Locale locale() {
        return locale;
    }

    public Set<String> paths() {
        return entries.keySet();
    }

    public boolean has(String path) {
        return entries.containsKey(path);
    }

    public Optional<String> string(String path) {
        return entries.get(path) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public Optional<List<String>> list(String path) {
        Object value = entries.get(path);
        if (value instanceof List<?> list) {
            return Optional.of((List<String>) list);
        }
        return value instanceof String s ? Optional.of(List.of(s)) : Optional.empty();
    }

    // A plain string counts as the "other" form. Files predating a key going plural keep a
    // single value, and copyDefaults only adds missing keys, so otherwise it'd resolve to nothing.
    @SuppressWarnings("unchecked")
    public Optional<Map<String, String>> plurals(String path) {
        Object value = entries.get(path);
        if (value instanceof Map<?, ?> map) {
            return Optional.of((Map<String, String>) map);
        }
        if (value instanceof String single) {
            return Optional.of(Map.of(PluralRules.FALLBACK, single));
        }
        return Optional.empty();
    }

    // falls back to "other" so a file with only one/other still works in a locale that needs "few"
    public Optional<String> plural(String path, long count) {
        return plurals(path).map(forms -> {
            String category = PluralRules.select(locale, count);
            String form = forms.get(category);
            return form != null ? form : forms.get(PluralRules.FALLBACK);
        });
    }
}
