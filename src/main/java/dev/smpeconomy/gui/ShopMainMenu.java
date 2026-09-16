package dev.smpeconomy.gui;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.service.MarketService;
import dev.smpeconomy.service.ShopService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * 5-row shop landing page.
 *
 * Row 1 (header):  History(2)  PlayerHead(4)  Top Items(6)
 * Row 2:           glass trim
 * Row 3 (18-26):   server shop category buttons, centred
 * Row 4:           glass trim
 * Row 5 (footer):  Player Shop(40, centre)
 */
public final class ShopMainMenu extends BaseGui {

    private static final TextColor GOLD = GuiUtil.GOLD;
    private static final TextColor GRAY = GuiUtil.GRAY;

    private static final int SIZE = 45;

    // Header
    private static final int SLOT_HISTORY   = 2;
    private static final int SLOT_HEAD      = 4;
    private static final int SLOT_TOP_ITEMS = 6;

    // Category buttons live in row 3 (slots 18-26).
    private static final int SECTION_ROW_START = 18;
    private static final int SECTION_ROW_SIZE  = 9;

    // Footer
    private static final int SLOT_PLAYER = 40;

    private final ConfigManager config;
    private final ShopService shopService;
    private final MarketService marketService;

    public ShopMainMenu(ConfigManager config, ShopService shopService, MarketService marketService) {
        super(SIZE, CoreKeys.SHOP_MAIN_TITLE);
        this.config        = config;
        this.shopService   = shopService;
        this.marketService = marketService;
    }

    @Override
    protected void populate() {
        ItemStack glass = makeGlass();
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, glass);

        // ── Header ────────────────────────────────────────────────────────────
        inventory.setItem(SLOT_HEAD, makePlayerHead());

        inventory.setItem(SLOT_HISTORY, makeHistoryIcon());
        onClick(SLOT_HISTORY, event -> {
            Player p = (Player) event.getWhoClicked();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin,
                () -> new PlayerShopHistoryMenu(config, plugin.getTransactionRepository(), backToShop(p)).open(p));
        });

        inventory.setItem(SLOT_TOP_ITEMS, makeTopItemsIcon());
        onClick(SLOT_TOP_ITEMS, event -> {
            Player p = (Player) event.getWhoClicked();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin,
                () -> new MarketStatsMenu(config, plugin.getTransactionRepository(), backToShop(p)).open(p));
        });

        // ── Category buttons — centred in row 3 ───────────────────────────────
        List<ShopSection> sections = config.getShopSections();
        int count     = Math.min(sections.size(), SECTION_ROW_SIZE);
        int startSlot = SECTION_ROW_START + (SECTION_ROW_SIZE - count) / 2;

        for (int i = 0; i < count; i++) {
            ShopSection section = sections.get(i);
            int slot = startSlot + i;
            inventory.setItem(slot, makeSectionIcon(section));
            onClick(slot, event -> {
                Player p = (Player) event.getWhoClicked();
                CustomEconomy plugin = CustomEconomy.getInstance();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    new ShopCategoryMenu(section, config, shopService, marketService).open(p));
            });
        }

        // ── Footer ────────────────────────────────────────────────────────────
        inventory.setItem(SLOT_PLAYER, makePlayerShopIcon());
        onClick(SLOT_PLAYER, event -> {
            Player p = (Player) event.getWhoClicked();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin,
                () -> new PlayerShopMenu(config, plugin.getPlayerShopService()).open(p));
        });
    }

    /** Reusable "return to this landing page" callback. */
    private Runnable backToShop(Player p) {
        CustomEconomy plugin = CustomEconomy.getInstance();
        return () -> plugin.getServer().getScheduler().runTask(plugin,
            () -> new ShopMainMenu(config, plugin.getShopService(), plugin.getMarketService()).open(p));
    }

    // ── Icon builders ─────────────────────────────────────────────────────────

    private Component title(String text) {
        return GuiUtil.title(text, GOLD);
    }

    private ItemStack makePlayerHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (viewer != null) {
            meta.setOwningPlayer(viewer);
            meta.displayName(title(viewer.getName()));
            meta.lore(List.of(
                Component.empty(),
                messages.lore(CoreKeys.SHOP_MAIN_BALANCE, TokenBag.of().put("balance", dev.smpeconomy.util.FormatUtil.formatMoney(
                        CustomEconomy.getInstance().getVaultHook().getBalance(viewer),
                        config.getCurrencySymbol())))
            ));
        } else {
            meta.displayName(title(messages.raw(CoreKeys.SHOP_MAIN_ICON_SERVER_SHOP)));
        }
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack makeTopItemsIcon() {
        ItemStack item = new ItemStack(Material.BEACON);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(title(messages.raw(CoreKeys.SHOP_MAIN_ICON_TOP_ITEMS)));
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(messages.loreList(CoreKeys.SHOP_MAIN_TOP_ITEMS_LORE));
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.SHOP_MAIN_CLICK_TO_OPEN));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeHistoryIcon() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(title(messages.raw(CoreKeys.SHOP_MAIN_ICON_HISTORY)));
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(messages.loreList(CoreKeys.SHOP_MAIN_HISTORY_LORE));
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.SHOP_MAIN_CLICK_TO_OPEN));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePlayerShopIcon() {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(title(messages.raw(CoreKeys.SHOP_MAIN_ICON_PLAYER_SHOP)));
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(messages.loreList(CoreKeys.SHOP_MAIN_PLAYER_SHOP_LORE));
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.SHOP_MAIN_CLICK_TO_OPEN));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeSectionIcon(ShopSection section) {
        ItemStack icon = new ItemStack(section.getIcon());
        ItemMeta meta  = icon.getItemMeta();
        meta.displayName(title(section.getDisplayName()));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.SHOP_MAIN_SECTION_ITEM_COUNT, TokenBag.of().put("count", section.getEntries().size())),
            Component.empty(),
            messages.lore(CoreKeys.SHOP_MAIN_CLICK_TO_BROWSE)
        ));
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeGlass() {
        return GuiUtil.glass();
    }
}
