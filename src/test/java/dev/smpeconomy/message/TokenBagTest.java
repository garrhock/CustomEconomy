package dev.smpeconomy.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBagTest {

    @Test
    void substitutesEveryOccurrence() {
        String out = TokenBag.of().put("item", "Diamond").put("quantity", 64)
                .applyTo("{quantity}x {item} ({item})");
        assertEquals("64x Diamond (Diamond)", out);
    }

    @Test
    void leavesUnknownPlaceholdersAlone() {
        // Validation is what catches these; rendering must not corrupt the text.
        assertEquals("{price}", TokenBag.of().put("item", "x").applyTo("{price}"));
    }

    @Test
    void convertsValuesWithoutCallerCeremony() {
        assertEquals("3", TokenBag.of().put("n", 3).applyTo("{n}"));
        assertEquals("2.5", TokenBag.of().put("n", 2.5).applyTo("{n}"));
    }

    @Test
    void namesReportWhatWasSupplied() {
        TokenBag bag = TokenBag.of().put("a", 1).put("b", 2);
        assertTrue(bag.names().contains("a"));
        assertTrue(bag.names().contains("b"));
        assertEquals(2, bag.names().size());
    }

    @Test
    void sharedEmptyBagCannotBeMutated() {
        assertThrows(UnsupportedOperationException.class, () -> TokenBag.empty().put("a", 1));
    }
}
