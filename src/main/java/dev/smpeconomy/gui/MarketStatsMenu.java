package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.repository.TransactionRepository;
import dev.smpeconomy.model.TopItem;
import dev.smpeconomy.model.Transaction.Source;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Top Items leaderboard — 6-row chest.
 *
 * Header:
 *   slot 4 = Controls (paper) — Left Click cycles Metric (Sold/Bought),
 *            Right Click cycles Period (Daily/Weekly/Monthly/All Time),
 *            Swap Hands (F) cycles Sort-by (Money/Quantity).
 *   slot 5 = Scope (compass) — click cycles Server / Player / Both.
 * Content (rows 2-5, slots 9-44): up to 36 ranked items.
 * Footer: slot 45 = back.
 */
public final class MarketStatsMenu extends BaseGui {

    // ── State enums ───────────────────────────────────────────────────────────

    private enum TimeRange {
        DAILY("Daily", 1),
        WEEKLY("Weekly", 7),
        MONTHLY("Monthly", 30),
        ALL_TIME("All Time", -1);

        final String label;
        final int days;
        TimeRange(String label, int days) { this.label = label; this.days = days; }

        TimeRange next() { return values()[(ordinal() + 1) % values().length]; }

        Instant since() { return days < 0 ? null : Instant.now().minus(days, ChronoUnit.DAYS); }
    }

    private enum Metric {
        SALES("Sold"),
        PURCHASES("Bought");

        final String label;
        Metric(String label) { this.label = label; }

        Metric toggle() { return this == SALES ? PURCHASES : SALES; }
    }

    private enum SortBy {
        QUANTITY("Quantity"),
        MONEY("Money");

        final String label;
        SortBy(String label) { this.label = label; }

        SortBy toggle() { return this == QUANTITY ? MONEY : QUANTITY; }

        TransactionRepository.SortBy toRepo() {
            return this == MONEY ? TransactionRepository.SortBy.MONEY : TransactionRepository.SortBy.QUANTITY;
        }
    }

    private enum ShopScope {
        SERVER("Server Shop"),
        PLAYER("Player Shop"),
        BOTH("Server + Player Shop");

        final String label;
        ShopScope(String label) { this.label = label; }

        ShopScope next() { return values()[(ordinal() + 1) % values().length]; }
    }

    // ── Colours ───────────────────────────────────────────────────────────────

    private static final TextColor GOLD   = GuiUtil.GOLD;
    private static final TextColor SILVER = TextColor.color(0xAAAAAA);
    private static final TextColor BRONZE = TextColor.color(0xFF7700);
    private static final TextColor GREEN  = GuiUtil.GREEN;
    private static final TextColor GRAY   = GuiUtil.GRAY;
    private static final TextColor RED    = GuiUtil.RED;
    private static final TextColor AQUA   = GuiUtil.AQUA;
    private static final TextColor WHITE  = TextColor.color(0xFFFFFF);

    private static final int SLOT_CONTROLS = 4;
    private static final int SLOT_SCOPE    = 5;
    private static final int SLOT_BACK     = 45;
    private static final int SLOT_LOADING  = 22;

    // ── Fields ────────────────────────────────────────────────────────────────

    private final ConfigManager config;
    private final TransactionRepository txRepo;
    private final Runnable onBack;

    private TimeRange timeRange = TimeRange.DAILY;
    private Metric    metric    = Metric.SALES;
    private SortBy    sortBy    = SortBy.QUANTITY;
    private ShopScope shopScope = ShopScope.SERVER;

    public MarketStatsMenu(ConfigManager config, TransactionRepository txRepo, Runnable onBack) {
        super(54, Component.text("Top Items", GRAY).decoration(TextDecoration.BOLD, true));
        this.config  = config;
        this.txRepo  = txRepo;
        this.onBack  = onBack;
    }

    // ── Swap-offhand intercept (F key, any slot) → cycle sort-by ───────────────

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            sortBy = sortBy.toggle();
            populate();
            return;
        }
        super.handleClick(event);
    }

    // ── Populate ──────────────────────────────────────────────────────────────

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        Player player = viewer;
        if (player == null) return;

        // Glass frames the header (row 1) and footer (row 6); content stays empty.
        ItemStack glass = makeGlass();
        for (int i = 0; i < 9; i++)   inventory.setItem(i, glass);
        for (int i = 45; i < 54; i++) inventory.setItem(i, glass);

        // ── Controls button ───────────────────────────────────────────────────
        inventory.setItem(SLOT_CONTROLS, makeControls());
        onClick(SLOT_CONTROLS, e -> {
            if (e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT) {
                timeRange = timeRange.next();   // Right = period
            } else {
                metric = metric.toggle();       // Left = metric
            }
            populate();
        });

        // ── Scope button ──────────────────────────────────────────────────────
        inventory.setItem(SLOT_SCOPE, makeScopeButton());
        onClick(SLOT_SCOPE, e -> { shopScope = shopScope.next(); populate(); });

        // ── Back ──────────────────────────────────────────────────────────────
        inventory.setItem(SLOT_BACK, makeNav(Material.RED_STAINED_GLASS_PANE, "Back", RED));
        onClick(SLOT_BACK, e -> {
            player.closeInventory();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });

        // ── Leaderboard entries (loaded off-thread) ───────────────────────────
        inventory.setItem(SLOT_LOADING, makeLoading());
        List<Source> sources = sourcesFor(shopScope, metric);
        TransactionRepository.SortBy repoSort = sortBy.toRepo();
        asyncLoad(
            () -> txRepo.getTopItems(sources, timeRange.since(), repoSort, 36),
            items -> {
                inventory.setItem(SLOT_LOADING, null);
                for (int s = 9; s <= 44; s++) inventory.setItem(s, null);
                if (items.isEmpty()) {
                    inventory.setItem(SLOT_LOADING, makeEmpty());
                } else {
                    for (int i = 0; i < items.size(); i++) {
                        inventory.setItem(9 + i, makeEntryIcon(items.get(i), i + 1));
                    }
                }
            });
    }

    private ItemStack makeLoading() {
        return GuiUtil.loading();
    }

    // ── Source mapping ────────────────────────────────────────────────────────

    private List<Source> sourcesFor(ShopScope scope, Metric m) {
        List<Source> list = new ArrayList<>();
        if (scope == ShopScope.SERVER || scope == ShopScope.BOTH) {
            if (m == Metric.PURCHASES) list.add(Source.SHOP_BUY);
            else list.addAll(List.of(Source.SELL_HAND, Source.SELL_INVENTORY,
                                     Source.SELL_GUI, Source.AUTOSELL));
        }
        if (scope == ShopScope.PLAYER || scope == ShopScope.BOTH) {
            list.add(m == Metric.PURCHASES ? Source.PLAYER_BUY : Source.PLAYER_SELL);
        }
        return list;
    }

    // ── Icon builders ─────────────────────────────────────────────────────────

    private ItemStack makeControls() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("Top Items Controls", GOLD)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  Period:  " + timeRange.label, AQUA).decoration(TextDecoration.ITALIC, false),
            Component.text("  Metric:  " + metric.label, AQUA).decoration(TextDecoration.ITALIC, false),
            Component.text("  Sort by: " + sortBy.label, AQUA).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("  Left Click   → change metric (Sold/Bought)", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  Right Click  → change period", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  Swap Hands   → change sort (Money/Quantity)", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeScopeButton() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("Scope: " + shopScope.label, GOLD)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  Which shop's trades to rank.", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("  Click to cycle Server / Player / Both", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeEntryIcon(TopItem entry, int rank) {
        Material mat = Material.matchMaterial(entry.itemKey());
        ItemStack icon = new ItemStack(mat != null ? mat : Material.PAPER);
        ItemMeta meta  = icon.getItemMeta();

        TextColor nameColor = switch (rank) {
            case 1 -> GOLD;
            case 2 -> SILVER;
            case 3 -> BRONZE;
            default -> WHITE;
        };
        String rankPrefix = switch (rank) {
            case 1 -> "🥇 #1  ";
            case 2 -> "🥈 #2  ";
            case 3 -> "🥉 #3  ";
            default -> "#" + rank + "  ";
        };

        String sym = config.getCurrencySymbol();
        meta.displayName(Component.text(rankPrefix + entry.itemKey().replace('_', ' '), nameColor)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));

        meta.lore(List.of(
            Component.empty(),
            Component.text("  Volume: " + String.format("%,d", entry.totalQuantity()) + " units", GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("  Value:  " + FormatUtil.formatMoney(entry.totalValue(), sym), GREEN)
                .decoration(TextDecoration.ITALIC, false)
        ));

        if (rank <= 3) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeEmpty() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("No data for this range yet.", GRAY)
            .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeNav(Material mat, String label, TextColor color) {
        return GuiUtil.nav(mat, label, color);
    }

    private ItemStack makeGlass() {
        return GuiUtil.glass();
    }
}
