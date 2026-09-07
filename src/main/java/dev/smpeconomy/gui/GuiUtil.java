package dev.smpeconomy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared building blocks for the inventory menus — a single source of truth for
 * the colour palette and the small item/text builders that were previously
 * duplicated across ~10 GUI classes.
 *
 * Layout (slot positions, row counts) stays the responsibility of each menu;
 * this class only standardizes the look of common pieces.
 */
public final class GuiUtil {

    private GuiUtil() {}

    // ── Palette ────────────────────────────────────────────────────────────────

    public static final TextColor GOLD  = TextColor.color(0xFFAA00);
    public static final TextColor GREEN = TextColor.color(0x55FF55);
    public static final TextColor GRAY  = NamedTextColor.GRAY;
    public static final TextColor RED   = NamedTextColor.RED;
    public static final TextColor AQUA  = NamedTextColor.AQUA;
    public static final TextColor BLUE  = TextColor.color(0x5555FF);

    // ── Text builders (bake the decoration boilerplate) ────────────────────────

    /** A non-italic lore/label line in the given colour. */
    public static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** A non-italic gray lore line. */
    public static Component line(String text) {
        return line(text, GRAY);
    }

    /** A non-italic, bold display-name. */
    public static Component title(String text, TextColor color) {
        return Component.text(text, color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true);
    }

    // ── Item builders ──────────────────────────────────────────────────────────

    /** A blank gray glass filler pane. */
    public static ItemStack glass() {
        return glass(Material.GRAY_STAINED_GLASS_PANE);
    }

    /** A blank filler pane of the given material. */
    public static ItemStack glass(Material paneMaterial) {
        ItemStack pane = new ItemStack(paneMaterial);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        pane.setItemMeta(meta);
        return pane;
    }

    /** A labelled navigation button (back/prev/next/etc.). */
    public static ItemStack nav(Material mat, String label, TextColor color) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line(label, color));
        item.setItemMeta(meta);
        return item;
    }

    /** A "Page x / y" paper indicator. */
    public static ItemStack pageIcon(int current, int total) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line("Page " + current + " / " + total, GRAY));
        item.setItemMeta(meta);
        return item;
    }

    /** A "Page x" paper indicator (total unknown). */
    public static ItemStack pageIcon(int current) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line("Page " + current, GRAY));
        item.setItemMeta(meta);
        return item;
    }

    /** The "Loading…" clock placeholder shown while an async query runs. */
    public static ItemStack loading() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line("Loading…", GRAY));
        item.setItemMeta(meta);
        return item;
    }

    // ── Time helpers ───────────────────────────────────────────────────────────

    /**
     * A short countdown to {@code future}, showing at most two units:
     * "5d 3h", "3h 4m", "12m", "<1m", or "expired".
     */
    public static String timeUntil(Instant future) {
        Duration d = Duration.between(Instant.now(), future);
        if (d.isNegative() || d.isZero()) return "expired";
        long days = d.toDays();
        long hours = d.toHoursPart();
        long mins = d.toMinutesPart();
        if (days > 0)  return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        if (mins > 0)  return mins + "m";
        return "<1m";
    }
}
