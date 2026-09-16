package dev.smpeconomy.message;

import java.util.Locale;
import java.util.Set;

/**
 * Plural form selection per locale. CLDR category names, but hand-rolled — ICU4J is about
 * a megabyte shaded and not worth it here.
 */
public final class PluralRules {

    public static final Set<String> CATEGORIES = Set.of("zero", "one", "two", "few", "many", "other");

    /** The one category a plural message must always have. */
    public static final String FALLBACK = "other";

    // languages where only 1 is singular
    private static final Set<String> ONE_OTHER = Set.of(
            "en", "de", "nl", "sv", "da", "no", "nb", "nn", "fi", "et",
            "it", "es", "pt", "el", "bg", "hu", "tr");

    private PluralRules() {
    }

    // Unknown languages always get "other" — wrong grammar, but never a missing message.
    public static String select(Locale locale, long count) {
        String language = locale == null ? "" : locale.getLanguage();
        if (ONE_OTHER.contains(language)) {
            return count == 1 ? "one" : FALLBACK;
        }
        return FALLBACK;
    }

    public static boolean isCategory(String name) {
        return CATEGORIES.contains(name);
    }
}
