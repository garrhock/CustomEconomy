package dev.smpeconomy.message;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Marks a key as plural and names the token carrying the count. */
public record Plural(String countToken, Set<String> tokens) {

    public static Plural on(String countToken, String... others) {
        Set<String> all = new LinkedHashSet<>();
        all.add(countToken);
        all.addAll(List.of(others));
        return new Plural(countToken, Set.copyOf(all));
    }
}
