package dev.smpeconomy.message;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluralRulesTest {

    @Test
    void englishSplitsOneFromEverythingElse() {
        assertEquals("one", PluralRules.select(Locale.ENGLISH, 1));
        assertEquals("other", PluralRules.select(Locale.ENGLISH, 0));
        assertEquals("other", PluralRules.select(Locale.ENGLISH, 2));
        assertEquals("other", PluralRules.select(Locale.ENGLISH, 64));
    }

    @Test
    void germanFollowsTheSameRule() {
        assertEquals("one", PluralRules.select(Locale.GERMAN, 1));
        assertEquals("other", PluralRules.select(Locale.GERMAN, 3));
    }

    @Test
    void unknownLanguageAlwaysUsesTheFallbackSoNothingIsEverMissing() {
        Locale unsupported = Locale.forLanguageTag("cy");
        assertEquals("other", PluralRules.select(unsupported, 1));
        assertEquals("other", PluralRules.select(unsupported, 5));
    }

    @Test
    void nullLocaleDoesNotThrow() {
        assertEquals("other", PluralRules.select(null, 1));
    }

    @Test
    void categoriesAreTheCldrNames() {
        assertTrue(PluralRules.isCategory("few"));
        assertTrue(PluralRules.isCategory("other"));
        assertTrue(!PluralRules.isCategory("plural"));
    }
}
