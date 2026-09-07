package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.service.MarketService;
import dev.smpeconomy.service.WorthService;
import dev.smpeconomy.util.FormatUtil;
import dev.smpeconomy.util.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /worths — a read-only, paginated, searchable catalog of every sellable item in
 * items.yml. Each entry shows the price the viewing player would actually earn
 * (base × their category multiplier × market factor) with a live trend arrow.
 *
 * 6-row layout (glass frames header + footer only):
 *   Header: Sort(3)  Search(4, centre)
 *   Content: slots 9-44 (36 per page)
 *   Footer: Back(45, closes), Prev(48), Page(49), Next(50)
 */
public final class WorthsMenu extends BaseGui {

    public enum WorthSort { PRICE_HIGH, PRICE_LOW, ALPHA_AZ, ALPHA_ZA, CATEGORY }

    private static final int ITEMS_PER_PAGE = 36;
    private static final int CONTENT_START  = 9;
    private static final int SLOT_SORT       = 3;
    private static final int SLOT_SEARCH     = 4;
    private static final int SLOT_BACK       = 45;
    private static final int SLOT_PREV       = 48;
    private static final int SLOT_PAGE       = 49;
    private static final int SLOT_NEXT       = 50;
    private static final int SLOT_EMPTY      = 22;

    private final ConfigManager config;
    private final WorthService worthService;
    private final MarketService marketService;

    private WorthSort sort = WorthSort.PRICE_HIGH;
    private String filter  = "";
    private int page        = 0;

    public WorthsMenu(ConfigManager config, WorthService worthService, MarketService marketService) {
        super(54, Component.text("Item Values", GuiUtil.GRAY).decoration(TextDecoration.BOLD, true));
        this.config        = config;
        this.worthService  = worthService;
        this.marketService = marketService;
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        Player player = viewer;
        if (player == null) return;

        // Glass frames the header (row 1) and footer (row 6); content stays empty.
        ItemStack glass = GuiUtil.glass();
        for (int i = 0; i < 9; i++)   inventory.setItem(i, glass);
        for (int i = 45; i < 54; i++) inventory.setItem(i, glass);

        // ── Header ────────────────────────────────────────────────────────────
        inventory.setItem(SLOT_SORT, makeSortIcon());
        onClick(SLOT_SORT, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new WorthsFilterMenu(sort, chosen -> {
                    sort = chosen;
                    page = 0;
                    plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
                }, () -> this.open(player)).open(player));
        });

        inventory.setItem(SLOT_SEARCH, makeSearchIcon());
        onClick(SLOT_SEARCH, e -> {
            if (e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT) {
                filter = "";
                page = 0;
                populate();
                return;
            }
            CustomEconomy plugin = CustomEconomy.getInstance();
            player.closeInventory();
            plugin.getChatInputService().prompt(player, "§eType an item name to search:", q -> {
                filter = q.trim().toLowerCase(Locale.ROOT);
                page = 0;
                plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
            });
        });

        // ── Build the working list ────────────────────────────────────────────
        List<ItemWorth> items = buildList();
        int totalPages = Math.max(1, (int) Math.ceil(items.size() / (double) ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int start = page * ITEMS_PER_PAGE;
        int end   = Math.min(start + ITEMS_PER_PAGE, items.size());

        if (items.isEmpty()) {
            inventory.setItem(SLOT_EMPTY, makeEmpty());
        } else {
            for (int i = start; i < end; i++) {
                inventory.setItem(CONTENT_START + (i - start), makeItemIcon(items.get(i)));
                // read-only: no click handler
            }
        }

        // ── Footer ────────────────────────────────────────────────────────────
        inventory.setItem(SLOT_BACK, GuiUtil.nav(Material.RED_STAINED_GLASS_PANE, "Close", GuiUtil.RED));
        onClick(SLOT_BACK, e -> e.getWhoClicked().closeInventory());

        if (page > 0) {
            inventory.setItem(SLOT_PREV, GuiUtil.nav(Material.ARROW, "Previous Page", GuiUtil.GOLD));
            onClick(SLOT_PREV, e -> { page--; populate(); });
        }
        inventory.setItem(SLOT_PAGE, GuiUtil.pageIcon(page + 1, totalPages));
        if (page < totalPages - 1) {
            inventory.setItem(SLOT_NEXT, GuiUtil.nav(Material.ARROW, "Next Page", GuiUtil.GOLD));
            onClick(SLOT_NEXT, e -> { page++; populate(); });
        }
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private List<ItemWorth> buildList() {
        List<ItemWorth> items = new ArrayList<>();
        for (ItemWorth w : worthService.getItemWorthMap().values()) {
            if (!filter.isEmpty()) {
                String name = w.getDisplayName().toLowerCase(Locale.ROOT);
                String key  = w.getKey().toLowerCase(Locale.ROOT);
                if (!name.contains(filter) && !key.contains(filter)) continue;
            }
            items.add(w);
        }
        items.sort(comparator());
        return items;
    }

    private Comparator<ItemWorth> comparator() {
        return switch (sort) {
            case PRICE_HIGH -> Comparator.comparingDouble(this::playerPrice).reversed();
            case PRICE_LOW  -> Comparator.comparingDouble(this::playerPrice);
            case ALPHA_AZ   -> Comparator.comparing(w -> w.getDisplayName().toLowerCase(Locale.ROOT));
            case ALPHA_ZA   -> Comparator.comparing((ItemWorth w) -> w.getDisplayName().toLowerCase(Locale.ROOT)).reversed();
            case CATEGORY   -> Comparator.comparing((ItemWorth w) -> w.getCategory().name())
                                         .thenComparing(w -> w.getDisplayName().toLowerCase(Locale.ROOT));
        };
    }

    /** The price the viewing player would actually earn per unit. */
    private double playerPrice(ItemWorth w) {
        double mult   = worthService.getMultiplier(viewer, w.getCategory());
        double factor = marketService.getFactor(w.getKey());
        return w.getBasePrice() * mult * factor;
    }

    // ── Icons ─────────────────────────────────────────────────────────────────

    private ItemStack makeItemIcon(ItemWorth w) {
        ItemStack icon = ItemUtil.createFromKey(w.getKey());
        if (icon == null) {
            Material mat = Material.matchMaterial(w.getKey());
            icon = new ItemStack(mat != null && mat.isItem() ? mat : Material.PAPER);
        }
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(GuiUtil.title(w.getDisplayName(), GuiUtil.GOLD));

        String sym = config.getCurrencySymbol();
        double price = playerPrice(w);

        // "Sell: $X  ↑ +12%" on one line.
        double factor = marketService.getFactor(w.getKey());
        int pct = (int) Math.round((factor - 1.0) * 100);
        String arrow;
        TextColor arrowColor;
        if (pct > 0)      { arrow = "↑ +" + pct + "%"; arrowColor = GuiUtil.GREEN; }
        else if (pct < 0) { arrow = "↓ " + pct + "%";  arrowColor = GuiUtil.RED; }
        else              { arrow = "↔";               arrowColor = GuiUtil.GRAY; }

        Component priceLine = Component.text("  Sell: ", GuiUtil.GRAY)
            .append(Component.text(FormatUtil.formatMoney(price, sym), GuiUtil.GREEN))
            .append(Component.text("   " + arrow, arrowColor))
            .decoration(TextDecoration.ITALIC, false);

        meta.lore(List.of(
            Component.empty(),
            GuiUtil.line("  " + w.getCategory().getDisplayName(), GuiUtil.AQUA),
            priceLine
        ));
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeSortIcon() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(GuiUtil.title("Sort", GuiUtil.GOLD));
        meta.lore(List.of(
            Component.empty(),
            GuiUtil.line("  Current: " + sortLabel(sort), GuiUtil.AQUA),
            Component.empty(),
            GuiUtil.line("  Click to change", GuiUtil.GRAY)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeSearchIcon() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(GuiUtil.title("Search", GuiUtil.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (filter.isEmpty()) {
            lore.add(GuiUtil.line("  Click to search by name", GuiUtil.GRAY));
        } else {
            lore.add(GuiUtil.line("  Showing: \"" + filter + "\"", GuiUtil.GREEN));
            lore.add(Component.empty());
            lore.add(GuiUtil.line("  Left-click: new search", GuiUtil.GRAY));
            lore.add(GuiUtil.line("  Right-click: clear", GuiUtil.GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeEmpty() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(GuiUtil.line(filter.isEmpty()
            ? "No sellable items found."
            : "No items match \"" + filter + "\".", GuiUtil.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private String sortLabel(WorthSort s) {
        return switch (s) {
            case PRICE_HIGH -> "Price: High → Low";
            case PRICE_LOW  -> "Price: Low → High";
            case ALPHA_AZ   -> "A → Z";
            case ALPHA_ZA   -> "Z → A";
            case CATEGORY   -> "By Category";
        };
    }
}
