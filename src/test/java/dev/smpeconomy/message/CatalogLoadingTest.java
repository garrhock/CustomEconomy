package dev.smpeconomy.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the YAML -> MessageCatalog hand-off. The other tests build catalogs from plain
 * maps, which is how plural blocks got lost in the loader without a single test failing.
 */
class CatalogLoadingTest {

    private static MessageCatalog load(String yaml) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        return MessageCatalog.of(Locale.ENGLISH, Messages.toPlainMap(config));
    }

    @Test
    void pluralBlockSurvivesLoading() {
        MessageCatalog catalog = load("""
                spawners:
                  collected:
                    one: Collected {amount} item.
                    other: Collected {amount} items.
                """);

        assertTrue(catalog.plurals("spawners.collected").isPresent(),
                "plural block was flattened away");
        assertEquals("Collected {amount} item.", catalog.plural("spawners.collected", 1).orElseThrow());
        assertEquals("Collected {amount} items.", catalog.plural("spawners.collected", 5).orElseThrow());
    }

    @Test
    void nestedScalarsStillFlatten() {
        MessageCatalog catalog = load("""
                spawners:
                  not-owner: You do not own this.
                  gui:
                    close: Close
                """);

        assertEquals("You do not own this.", catalog.string("spawners.not-owner").orElseThrow());
        assertEquals("Close", catalog.string("spawners.gui.close").orElseThrow());
    }

    @Test
    void listsSurviveLoading() {
        MessageCatalog catalog = load("""
                shop:
                  lore:
                    - first line
                    - second line
                """);

        assertEquals(2, catalog.list("shop.lore").orElseThrow().size());
    }
}
