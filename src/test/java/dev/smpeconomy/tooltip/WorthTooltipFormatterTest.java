package dev.smpeconomy.tooltip;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorthTooltipFormatterTest {

    // ── priceKey: must mirror ItemUtil#getKey ────────────────────────────────

    @Test
    void plainMaterialBecomesUppercaseKey() {
        assertEquals("DIAMOND", WorthTooltipFormatter.priceKey("diamond", null, false, null));
    }

    @Test
    void customModelDataIsAppendedLikeItemUtil() {
        assertEquals("DIAMOND_SWORD:7",
            WorthTooltipFormatter.priceKey("diamond_sword", 7, false, null));
    }

    @Test
    void itemsAdderIdWinsOverCustomModelData() {
        // ItemUtil checks the PDC first, so a custom-model-data value must not shadow it.
        assertEquals("ia:myitems:ruby",
            WorthTooltipFormatter.priceKey("diamond", 7, false, "MyItems:Ruby"));
    }

    @Test
    void blankItemsAdderIdFallsThrough() {
        assertEquals("DIAMOND", WorthTooltipFormatter.priceKey("diamond", null, false, "  "));
    }

    @Test
    void listFormCustomModelDataIsUnresolvable() {
        // The legacy int is not recoverable from the list form, so the key would not
        // match ItemUtil's. Returning null keeps the tooltip silent instead of wrong.
        assertNull(WorthTooltipFormatter.priceKey("diamond", null, true, null));
    }

    @Test
    void legacyCustomModelDataWinsOverListForm() {
        assertEquals("DIAMOND:3", WorthTooltipFormatter.priceKey("diamond", 3, true, null));
    }

    // ── renderTemplate: stack maths and tokens ───────────────────────────────

    @Test
    void stackTokenMultipliesByAmount() {
        assertEquals("64 x 10 = 640",
            WorthTooltipFormatter.renderTemplate("{amount} x {price} = {stack}", "", 10, 64, false));
    }

    @Test
    void splittingAStackHalvesTheStackValue() {
        // The drag bug: a 32-stack must not keep the 64-stack's total.
        assertEquals("320", WorthTooltipFormatter.renderTemplate("{stack}", "", 10, 32, false));
        assertEquals("640", WorthTooltipFormatter.renderTemplate("{stack}", "", 10, 64, false));
    }

    @Test
    void singleItemStackValueEqualsUnitPrice() {
        assertEquals("12.5|12.5",
            WorthTooltipFormatter.renderTemplate("{price}|{stack}", "", 12.5, 1, false));
    }

    @Test
    void currencyTokenIsSubstituted() {
        assertEquals("$640", WorthTooltipFormatter.renderTemplate("{currency}{stack}", "$", 10, 64, false));
    }

    @Test
    void everyOccurrenceOfATokenIsReplaced() {
        assertEquals("640/640", WorthTooltipFormatter.renderTemplate("{stack}/{stack}", "", 10, 64, false));
    }

    @Test
    void fullFormattingUsesThousandsSeparators() {
        assertEquals("1,234,567.89",
            WorthTooltipFormatter.renderTemplate("{stack}", "", 1_234_567.89, 1, false));
    }

    @Test
    void compactFormattingUsesSuffixes() {
        assertEquals("1.23M", WorthTooltipFormatter.renderTemplate("{stack}", "", 1_234_567.89, 1, true));
    }

    @Test
    void templateWithoutTokensIsUnchanged() {
        assertEquals("Not for sale",
            WorthTooltipFormatter.renderTemplate("Not for sale", "$", 0, 5, false));
    }

    // ── line: the rendered component ─────────────────────────────────────────

    @Test
    void lineParsesMiniMessageIntoPlainText() {
        Component c = WorthTooltipFormatter.line(
            "<gray>Worth: <green>{currency}{stack}</green></gray>", "$", 10, 64, false);
        assertEquals("Worth: $640", PlainTextComponentSerializer.plainText().serialize(c));
    }

    @Test
    void lineIsNotItalicSoLoreRendersLikeVanillaText() {
        Component c = WorthTooltipFormatter.line("<gray>Worth: {stack}</gray>", "$", 10, 1, false);
        assertEquals(TextDecoration.State.FALSE, c.decoration(TextDecoration.ITALIC));
    }
}
