package dev.smpeconomy.tooltip;

import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/**
 * Pure tooltip logic: price-table key derivation and worth-line rendering.
 *
 * Deliberately free of Bukkit and packetevents types so it can be unit tested
 * and so the packet listener stays a thin adapter over it.
 */
public final class WorthTooltipFormatter {

    private WorthTooltipFormatter() {}

    /**
     * Rebuilds the key {@code ItemUtil#getKey} would produce, from values that
     * can be lifted straight off a packet.
     *
     * Returns null when the key cannot be reproduced faithfully, so the caller
     * stays silent rather than quoting a price /sell would not honour.
     */
    public static String priceKey(String materialName,
                                  Integer customModelData,
                                  boolean hasCustomModelDataList,
                                  String itemsAdderId) {
        // 1. ItemsAdder id, which ItemUtil reads from the PDC before anything else.
        if (itemsAdderId != null && !itemsAdderId.isBlank()) {
            return "ia:" + itemsAdderId.toLowerCase(Locale.ROOT);
        }
        if (materialName == null || materialName.isBlank()) return null;

        String material = materialName.toUpperCase(Locale.ROOT);

        // 2. Custom model data.
        if (customModelData != null) return material + ":" + customModelData;

        // 1.21.4+ stores custom model data as a list. Bukkit still reports
        // hasCustomModelData() for those, but the legacy int it returns is not
        // recoverable here, so the key would not match ItemUtil's.
        if (hasCustomModelDataList) return null;

        // 3. Plain material.
        return material;
    }

    /** Substitutes the worth tokens. {stack} is the whole stack, {price} one unit. */
    public static String renderTemplate(String template,
                                        String currencySymbol,
                                        double unitPrice,
                                        int amount,
                                        boolean compact) {
        return template
            .replace("{currency}", currencySymbol)
            .replace("{price}",    money(unitPrice, compact))
            .replace("{stack}",    money(unitPrice * amount, compact))
            .replace("{amount}",   String.valueOf(amount));
    }

    /** The rendered lore line. Italics are off so it reads like vanilla lore. */
    public static Component line(String template,
                                 String currencySymbol,
                                 double unitPrice,
                                 int amount,
                                 boolean compact) {
        String rendered = renderTemplate(template, currencySymbol, unitPrice, amount, compact);
        return FormatUtil.parse(rendered)
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String money(double value, boolean compact) {
        return compact
            ? FormatUtil.formatMoney(value, "")
            : FormatUtil.formatMoneyFull(value, "");
    }
}
