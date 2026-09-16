package dev.smpeconomy.gui;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.repository.TransactionRepository;
import dev.smpeconomy.model.Transaction;
import dev.smpeconomy.model.Transaction.Source;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Transaction history — 6-row chest.
 *
 * Two views driven by two toggle buttons, centred in the header:
 *   slot 4 = TRADE toggle  (Purchases ↔ Sales) — green/red concrete
 *   slot 5 = SHOP toggle   (Server Shop ↔ Player Shop) — blue concrete
 * Content (rows 2-5, slots 9-44): 36 entries per page
 * Footer (row 6): back(45)  prev(48)  page(49)  next(50)
 */
public final class PlayerShopHistoryMenu extends BaseGui {

    private enum ShopType  { SERVER, PLAYER }
    private enum TradeType { PURCHASES, SALES }

    private static final int SLOT_TRADE   = 4;
    private static final int SLOT_SHOP    = 5;
    private static final int SLOT_BACK    = 45;
    private static final int SLOT_PREV    = 48;
    private static final int SLOT_PAGE    = 49;
    private static final int SLOT_NEXT    = 50;
    private static final int SLOT_LOADING = 22;

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor RED   = GuiUtil.RED;
    private static final TextColor BLUE  = GuiUtil.BLUE;

    private final ConfigManager config;
    private final TransactionRepository txRepo;
    private final Runnable onBack;

    private ShopType  shopType  = ShopType.SERVER;
    private TradeType tradeType = TradeType.PURCHASES;
    private int page = 0;

    public PlayerShopHistoryMenu(ConfigManager config, TransactionRepository txRepo, Runnable onBack) {
        super(54, CoreKeys.PLAYERSHOP_HISTORY_TITLE);
        this.config  = config;
        this.txRepo  = txRepo;
        this.onBack  = onBack;
    }

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

        // ── Header ────────────────────────────────────────────────────────────
        inventory.setItem(SLOT_TRADE, makeTradeToggle());
        onClick(SLOT_TRADE, e -> {
            tradeType = (tradeType == TradeType.PURCHASES) ? TradeType.SALES : TradeType.PURCHASES;
            page = 0;
            populate();
        });

        inventory.setItem(SLOT_SHOP, makeShopToggle());
        onClick(SLOT_SHOP, e -> {
            shopType = (shopType == ShopType.SERVER) ? ShopType.PLAYER : ShopType.SERVER;
            page = 0;
            populate();
        });

        // ── Footer (static chrome) ────────────────────────────────────────────
        inventory.setItem(SLOT_BACK, makeNav(Material.RED_STAINED_GLASS_PANE, "Back", RED));
        onClick(SLOT_BACK, e -> {
            player.closeInventory();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });
        inventory.setItem(SLOT_LOADING, makeLoading());

        // ── Entries (loaded off-thread) ───────────────────────────────────────
        final java.util.UUID uuid = player.getUniqueId();
        final List<Source> sources = sourcesFor(shopType, tradeType);
        final int reqPage = page;
        asyncLoad(
            () -> {
                List<Transaction> entries = txRepo.getHistory(uuid, sources, reqPage);
                int total = txRepo.countHistory(uuid, sources);
                return new HistoryPage(entries, Math.max(1, (int) Math.ceil(total / 36.0)));
            },
            result -> {
                inventory.setItem(SLOT_LOADING, null);
                page = Math.max(0, Math.min(page, result.totalPages() - 1));
                List<Transaction> entries = result.entries();
                if (entries.isEmpty()) {
                    inventory.setItem(SLOT_LOADING, makeEmpty());
                } else {
                    for (int i = 0; i < entries.size(); i++) {
                        inventory.setItem(9 + i, makeEntryIcon(entries.get(i)));
                    }
                }
                if (page > 0) {
                    inventory.setItem(SLOT_PREV, makeNav(Material.ARROW, "Previous Page", GOLD));
                    onClick(SLOT_PREV, e -> { page--; populate(); });
                }
                inventory.setItem(SLOT_PAGE, makePageIcon(page + 1, result.totalPages()));
                if (page < result.totalPages() - 1) {
                    inventory.setItem(SLOT_NEXT, makeNav(Material.ARROW, "Next Page", GOLD));
                    onClick(SLOT_NEXT, e -> { page++; populate(); });
                }
            });
    }

    private record HistoryPage(List<Transaction> entries, int totalPages) {}

    private ItemStack makeLoading() {
        return GuiUtil.loading();
    }

    // ── Source mapping ────────────────────────────────────────────────────────

    private List<Source> sourcesFor(ShopType shop, TradeType trade) {
        return switch (shop) {
            case SERVER -> trade == TradeType.PURCHASES
                ? List.of(Source.SHOP_BUY)
                : List.of(Source.SELL_HAND, Source.SELL_INVENTORY, Source.SELL_GUI, Source.AUTOSELL, Source.SPAWNER);
            case PLAYER -> trade == TradeType.PURCHASES
                ? List.of(Source.PLAYER_BUY)
                : List.of(Source.PLAYER_SELL);
        };
    }

    // ── Icon builders ─────────────────────────────────────────────────────────

    private ItemStack makeEntryIcon(Transaction tx) {
        Material mat = Material.matchMaterial(tx.itemKey());
        ItemStack icon = new ItemStack(mat != null ? mat : Material.PAPER);
        ItemMeta meta  = icon.getItemMeta();

        String sym        = config.getCurrencySymbol();
        boolean isPurchase = tx.totalEarned() < 0;
        TextColor moneyColor = isPurchase ? RED : GREEN;
        String moneyPrefix   = isPurchase ? "-" : "+";
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_HISTORY_ENTRY_NAME, TokenBag.of().put("item", tx.itemKey().replace('_', ' '))));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_HISTORY_QTY, TokenBag.of().put("quantity", tx.quantity())));
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_HISTORY_PRICE, TokenBag.of().put("price", FormatUtil.formatMoney(Math.abs(tx.pricePerUnit()), sym))));
        lore.add(messages.lore(isPurchase ? CoreKeys.PLAYERSHOP_HISTORY_TOTAL_SPENT
                                          : CoreKeys.PLAYERSHOP_HISTORY_TOTAL_EARNED, TokenBag.of().put("total", FormatUtil.formatMoney(Math.abs(tx.totalEarned()), sym))));
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_HISTORY_WHEN, TokenBag.of().put("when", timeAgo(tx.createdAt()))));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeTradeToggle() {
        boolean isPurchases = tradeType == TradeType.PURCHASES;
        ItemStack item = new ItemStack(isPurchases ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(isPurchases ? CoreKeys.PLAYERSHOP_HISTORY_TOGGLE_PURCHASES
                                                   : CoreKeys.PLAYERSHOP_HISTORY_TOGGLE_SALES));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(isPurchases ? CoreKeys.PLAYERSHOP_HISTORY_PURCHASES_LORE
                                      : CoreKeys.PLAYERSHOP_HISTORY_SALES_LORE),
            Component.empty(),
            messages.lore(isPurchases ? CoreKeys.PLAYERSHOP_HISTORY_SWITCH_TO_SALES
                                      : CoreKeys.PLAYERSHOP_HISTORY_SWITCH_TO_PURCHASES)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeShopToggle() {
        boolean isServer = shopType == ShopType.SERVER;
        ItemStack item  = new ItemStack(Material.BLUE_CONCRETE);
        ItemMeta meta   = item.getItemMeta();
        meta.displayName(messages.lore(isServer ? CoreKeys.PLAYERSHOP_HISTORY_TOGGLE_SERVER_SHOP
                                                : CoreKeys.PLAYERSHOP_HISTORY_TOGGLE_PLAYER_SHOP));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(isServer ? CoreKeys.PLAYERSHOP_HISTORY_SERVER_SHOP_LORE
                                   : CoreKeys.PLAYERSHOP_HISTORY_PLAYER_SHOP_LORE),
            Component.empty(),
            messages.lore(isServer ? CoreKeys.PLAYERSHOP_HISTORY_SWITCH_TO_PLAYER_SHOP
                                   : CoreKeys.PLAYERSHOP_HISTORY_SWITCH_TO_SERVER_SHOP)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeEmpty() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_HISTORY_EMPTY));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePageIcon(int current, int total) {
        return GuiUtil.pageIcon(current, total);
    }

    private ItemStack makeNav(Material mat, String label, TextColor color) {
        return GuiUtil.nav(mat, label, color);
    }

    private ItemStack makeGlass() {
        return GuiUtil.glass();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String timeAgo(Instant past) {
        Duration d = Duration.between(past, Instant.now());
        if (d.toDays() >= 1)    return d.toDays() + "d ago";
        if (d.toHours() >= 1)   return d.toHours() + "h ago";
        if (d.toMinutes() >= 1) return d.toMinutes() + "m ago";
        return "just now";
    }

}
