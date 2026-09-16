package dev.smpeconomy.message;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCatalogTest {

    private MessageCatalog catalog(Map<String, Object> nested) {
        return MessageCatalog.of(Locale.ENGLISH, nested);
    }

    @Test
    void flattensNestedSectionsToDottedPaths() {
        MessageCatalog c = catalog(Map.of("playershop", Map.of("sell", Map.of("created", "made it"))));
        assertEquals("made it", c.string("playershop.sell.created").orElseThrow());
    }

    @Test
    void keepsListsAsMultiLineLore() {
        MessageCatalog c = catalog(Map.of("lore", List.of("one", "two")));
        assertEquals(List.of("one", "two"), c.list("lore").orElseThrow());
    }

    @Test
    void readsASingleStringAsAOneLineList() {
        // Lore blocks and single lines should be interchangeable at the call site.
        MessageCatalog c = catalog(Map.of("line", "only"));
        assertEquals(List.of("only"), c.list("line").orElseThrow());
    }

    @Test
    void treatsAMapOfPluralCategoriesAsOneMessageNotSeveral() {
        MessageCatalog c = catalog(Map.of("items", Map.of("one", "{count} item", "other", "{count} items")));
        assertTrue(c.plurals("items").isPresent());
        assertFalse(c.has("items.one"), "plural forms must not flatten into separate keys");
    }

    @Test
    void stillFlattensOrdinaryMapsThatMerelyLookNested() {
        MessageCatalog c = catalog(Map.of("shop", Map.of("title", "Shop")));
        assertTrue(c.has("shop.title"));
        assertFalse(c.plurals("shop").isPresent());
    }

    @Test
    void selectsThePluralFormForACount() {
        MessageCatalog c = catalog(Map.of("items", Map.of("one", "{count} item", "other", "{count} items")));
        assertEquals("{count} item", c.plural("items", 1).orElseThrow());
        assertEquals("{count} items", c.plural("items", 5).orElseThrow());
    }

    @Test
    void fallsBackToOtherWhenTheExactCategoryIsAbsent() {
        // A locale rule may produce "few" even though the file only supplies one/other.
        MessageCatalog c = catalog(Map.of("items", Map.of("other", "{count} items")));
        assertEquals("{count} items", c.plural("items", 1).orElseThrow());
    }

    @Test
    void readsAPlainStringAsTheOtherFormSoUpgradesDoNotBreak() {
        // A file predating a key becoming plural keeps a single value, and copyDefaults
        // only fills in absent keys — so the scalar has to remain usable.
        MessageCatalog c = catalog(Map.of("items", "{count} items"));
        assertEquals("{count} items", c.plural("items", 1).orElseThrow());
        assertEquals("{count} items", c.plural("items", 7).orElseThrow());
    }

    @Test
    void missingPathsResolveToEmptyRatherThanThrowing() {
        MessageCatalog c = MessageCatalog.empty(Locale.ENGLISH);
        assertTrue(c.string("nope").isEmpty());
        assertTrue(c.list("nope").isEmpty());
        assertTrue(c.plurals("nope").isEmpty());
        assertFalse(c.has("nope"));
    }
}
