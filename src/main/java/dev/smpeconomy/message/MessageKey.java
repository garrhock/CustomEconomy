package dev.smpeconomy.message;

import java.util.Set;

/**
 * A message the plugin can send. One enum per module so each owns its own text.
 *
 * Keys declare their tokens — that's what lets the loader check a translation and the
 * sender check a caller.
 */
public interface MessageKey {

    String path();

    /** Token names the text should contain, without braces. */
    default Set<String> declaredTokens() {
        return Set.of();
    }

    /** True if the value is a map of plural forms rather than one string. */
    default boolean plural() {
        return false;
    }

    /** Token carrying the count, for plural messages. */
    default String countToken() {
        return null;
    }
}
