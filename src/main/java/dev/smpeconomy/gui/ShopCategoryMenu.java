package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.service.MarketService;
import dev.smpeconomy.service.ShopService;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 3-row section item browser.
 *
 * Rows 1-2 (slots 0-17): up to 18 items per page
 * Row 3 (slots 18-26):   prev(18)  back(22)  next(26)
 */
public final class ShopCategoryMenu extends BaseGui {

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor RED   = GuiUtil.RED;
    private static final TextColor AQUA  = GuiUtil.AQUA;

    // Items live in the middle row (slots 9-17), one row per page, centred.
    private static final int ITEMS_PER_PAGE = 9;
    private static final int ITEM_ROW_START = 9;
    private static final int SLOT_PREV      = 18;
    private static final int SLOT_BACK      = 22;
    private static final int SLOT_NEXT      = 26;

    private final ShopSection section;
    private final ConfigManager config;
    private final ShopService shopService;
    private final MarketService marketService;
    private int page = 0;

    public ShopCategoryMenu(ShopSection section, ConfigManager config,
                             ShopService shopService, MarketService marketService) {
        super(27, Component.text("Shop — " + section.getDisplayName(), GRAY)
                .decoration(TextDecoration.BOLD, true));
        this.section       = section;
        this.config        = config;
        this.shopService   = shopService;
        this.marketService = marketService;
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        List<ShopEntry> entries = section.getEntries();
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        int start = page * ITEMS_PER_PAGE;
        int end   = Math.min(start + ITEMS_PER_PAGE, entries.size());
        int pageCount = end - start;

        // Fill background
        ItemStack glass = makeGlass();
        for (int s = 0; s < 27; s++) inventory.setItem(s, glass);

        // Items — single centred row (vertically middle row, horizontally centred)
        int rowStart = ITEM_ROW_START + (ITEMS_PER_PAGE - pageCount) / 2;
        for (int i = start; i < end; i++) {
            int slot = rowStart + (i - start);
            ShopEntry entry = entries.get(i);
            double dynPrice = marketService.getDynamicShopPrice(entry.material(), entry.unitPrice());
            inventory.setItem(slot, makeItemIcon(entry, dynPrice));
            onClick(slot, event -> handleBuy(event, entry));
        }

        // Nav row
        if (page > 0) {
            inventory.setItem(SLOT_PREV, makeNav(Material.ARROW, "Previous Page", GOLD));
            onClick(SLOT_PREV, e -> { page--; populate(); });
        }

        inventory.setItem(SLOT_BACK, makeNav(Material.RED_STAINED_GLASS_PANE, "Back to Shop", RED));
        onClick(SLOT_BACK, e -> {
            Player p = (Player) e.getWhoClicked();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin,
                () -> new ShopMainMenu(config, shopService, marketService).open(p));
        });

        if (page < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, makeNav(Material.ARROW, "Next Page", GOLD));
            onClick(SLOT_NEXT, e -> { page++; populate(); });
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private void handleBuy(InventoryClickEvent event, ShopEntry entry) {
        Player player = (Player) event.getWhoClicked();
        CustomEconomy plugin = CustomEconomy.getInstance();

        int multiplier = switch (event.getClick()) {
            case RIGHT -> 4;
            case SHIFT_LEFT, SHIFT_RIGHT -> 16;
            default -> 1;
        };

        int initialQty  = Math.max(1, entry.quantity() * multiplier);
        double dynPrice = marketService.getDynamicShopPrice(entry.material(), entry.unitPrice());

        plugin.getServer().getScheduler().runTask(plugin,
            () -> new BuyQuantityMenu(entry, initialQty, dynPrice, config, shopService,
                    () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player)))
                .open(player));
    }

    // ── Icons ─────────────────────────────────────────────────────────────────

    private ItemStack makeItemIcon(ShopEntry entry, double dynPrice) {
        String sym   = config.getCurrencySymbol();
        ItemStack icon = new ItemStack(entry.material());
        ItemMeta meta  = icon.getItemMeta();
        meta.displayName(Component.text(entry.displayName(), GOLD)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (entry.quantity() > 1) {
            lore.add(Component.text("  Qty per click: ×" + entry.quantity(), GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }

        double totalDyn = dynPrice * entry.quantity();
        Component priceLine = Component.text("  Price: ", GRAY)
                .append(Component.text(FormatUtil.formatMoney(totalDyn, sym), GREEN))
                .decoration(TextDecoration.ITALIC, false);

        String trend = marketService.getFactorDisplay(entry.material().name());
        if (!trend.equals("±0%")) {
            TextColor trendColor = trend.startsWith("+") ? RED : AQUA;
            priceLine = priceLine.append(Component.text("  " + trend, trendColor)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(priceLine);

        lore.add(Component.empty());
        lore.add(Component.text("  Left Click:   ×" + entry.quantity(), GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("  Right Click:  ×" + (entry.quantity() * 4), GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("  Shift+Click:  ×" + (entry.quantity() * 16), GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeNav(Material mat, String label, TextColor color) {
        return GuiUtil.nav(mat, label, color);
    }

    private ItemStack makeGlass() {
        return GuiUtil.glass();
    }
}
