package dev.smpeconomy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class FormatUtil {

    private static final DecimalFormat FULL_FORMAT;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // Thread-safe: DecimalFormat is not thread-safe; use ThreadLocal
    private static final ThreadLocal<DecimalFormat> COMPACT_FORMAT = ThreadLocal.withInitial(() ->
        new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US)));

    static {
        FULL_FORMAT = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
    }

    private FormatUtil() {}

    /**
     * Formats a monetary value with compact suffixes (K/M/B/T).
     * $1,234 → $1.23K  |  $4,500,000 → $4.5M
     */
    public static String formatMoney(double amount, String symbol) {
        if (amount < 1_000) {
            return symbol + COMPACT_FORMAT.get().format(amount);
        } else if (amount < 1_000_000) {
            return symbol + COMPACT_FORMAT.get().format(amount / 1_000) + "K";
        } else if (amount < 1_000_000_000) {
            return symbol + COMPACT_FORMAT.get().format(amount / 1_000_000) + "M";
        } else if (amount < 1_000_000_000_000.0) {
            return symbol + COMPACT_FORMAT.get().format(amount / 1_000_000_000) + "B";
        } else {
            return symbol + COMPACT_FORMAT.get().format(amount / 1_000_000_000_000.0) + "T";
        }
    }

    /** Full precision format — $1,234,567.89 */
    public static String formatMoneyFull(double amount, String symbol) {
        synchronized (FULL_FORMAT) {
            return symbol + FULL_FORMAT.format(amount);
        }
    }

    /** Plain number with commas — no symbol */
    public static String formatNumber(long n) {
        return COMPACT_FORMAT.get().format(n);
    }

    /** Multiplier display — 1.75x */
    public static String formatMultiplier(double mult) {
        return String.format("%.2fx", mult);
    }

    // ── Adventure component helpers ──────────────────────────────────────────

    public static Component money(double amount, String symbol) {
        return Component.text(formatMoney(amount, symbol), TextColor.color(0x55FF55));
    }

    public static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    public static Component info(String message) {
        return Component.text(message, NamedTextColor.GRAY);
    }

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }
}
