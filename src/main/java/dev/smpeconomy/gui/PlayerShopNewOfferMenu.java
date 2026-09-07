package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.service.ChatInputService;
import dev.smpeconomy.service.PlayerShopService;
import dev.smpeconomy.service.PlayerShopService.Result;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * New offer creation panel (3 rows, 27 slots).
 *
 * Layout:
 *   Row 1: all glass — slot 4 = NAME_TAG (BUY item search)
 *   Row 2: glass — slot 12 = RED_CONCRETE (sell), slot 13 = item slot, slot 14 = LIME_CONCRETE (buy)
 *   Row 3: all glass — slot 22 = BARRIER (back/cancel)
 *
 * SELL flow: player places cursor item on slot 13 → clicks red concrete → chat qty → chat price
 * BUY  flow: player clicks name tag → chat item name → menu reopens → clicks green concrete → chat qty → chat price
 */
public final class PlayerShopNewOfferMenu extends BaseGui {

    private static final int SLOT_SEARCH  = 4;
    private static final int SLOT_SELL    = 12;
    private static final int SLOT_ITEM    = 13;
    private static final int SLOT_BUY     = 14;
    private static final int SLOT_BACK    = 22;

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor RED   = GuiUtil.RED;

    private final ConfigManager config;
    private final PlayerShopService shopService;
    private final ChatInputService chatInput;
    private final Runnable onBack;

    private ItemStack selectedItem = null; // set by cursor-capture or name lookup

    public PlayerShopNewOfferMenu(ConfigManager config, PlayerShopService shopService,
                                   ChatInputService chatInput, Runnable onBack) {
        super(27, Component.text("New Offer", GRAY).decoration(TextDecoration.BOLD, true));
        this.config      = config;
        this.shopService = shopService;
        this.chatInput   = chatInput;
        this.onBack      = onBack;
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        ItemStack glass = makeGlass(GRAY);
        for (int i = 0; i < 27; i++) inventory.setItem(i, glass);

        // Name tag: prompts chat input for item name (BUY orders)
        inventory.setItem(SLOT_SEARCH, makeSearchIcon());
        onClick(SLOT_SEARCH, e -> handleSearch((Player) e.getWhoClicked()));

        // Center item slot
        inventory.setItem(SLOT_ITEM, selectedItem != null ? makePreview() : makePlaceholder());
        onClick(SLOT_ITEM, this::handleItemSlotClick);

        // Sell (red) concrete
        inventory.setItem(SLOT_SELL, makeSellButton());
        onClick(SLOT_SELL, e -> {
            if (selectedItem == null) {
                ((Player) e.getWhoClicked()).sendMessage(
                    Component.text("Place an item in the center slot first.", RED)
                        .decoration(TextDecoration.ITALIC, false));
                return;
            }
            handleCreateListing((Player) e.getWhoClicked(), false);
        });

        // Buy (green) concrete
        inventory.setItem(SLOT_BUY, makeBuyButton());
        onClick(SLOT_BUY, e -> {
            if (selectedItem == null) {
                ((Player) e.getWhoClicked()).sendMessage(
                    Component.text("Search for an item first using the name tag.", RED)
                        .decoration(TextDecoration.ITALIC, false));
                return;
            }
            handleCreateListing((Player) e.getWhoClicked(), true);
        });

        // Back
        inventory.setItem(SLOT_BACK, makeBack());
        onClick(SLOT_BACK, e -> {
            Player p = (Player) e.getWhoClicked();
            chatInput.cancel(p.getUniqueId());
            p.closeInventory();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });
    }

    // ── Prevent shift-clicks from filling GUI slots with inventory items ───────

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (event.getClickedInventory() != null
                && event.getClickedInventory().equals(
                    ((Player) event.getWhoClicked()).getInventory())
                && event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }
        super.handleClick(event);
    }

    // ── Click handlers ────────────────────────────────────────────────────────

    private void handleItemSlotClick(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        if (cursor != null && cursor.getType() != Material.AIR) {
            ItemStack template = cursor.clone();
            template.setAmount(1);
            selectedItem = template;
            populate();
        }
    }

    private void handleSearch(Player player) {
        player.closeInventory();
        chatInput.prompt(player, "§eEnter the item name (e.g. stone_bricks):", input -> {
            Material mat = Material.matchMaterial(input.trim().toUpperCase()
                .replace(' ', '_').replace('-', '_'));
            if (mat == null || mat == Material.AIR) {
                player.sendMessage(Component.text(
                    "Unknown item: " + input + ". Try the exact Minecraft material name.", RED)
                    .decoration(TextDecoration.ITALIC, false));
                CustomEconomy plugin = CustomEconomy.getInstance();
                plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
                return;
            }
            selectedItem = new ItemStack(mat, 1);
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
        });
    }

    private void handleCreateListing(Player player, boolean isBuy) {
        if (selectedItem == null) return;

        CustomEconomy plugin = CustomEconomy.getInstance();

        // Determine max qty for sell (inventory count); buy orders are unbounded.
        int maxQty = isBuy ? Integer.MAX_VALUE
            : countInInventory(player, selectedItem.getType());

        if (!isBuy && maxQty == 0) {
            player.closeInventory();
            player.sendMessage(Component.text(
                "You don't have any " + selectedItem.getType().name().replace('_', ' ')
                + " in your inventory.", RED).decoration(TextDecoration.ITALIC, false));
            plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
            return;
        }

        // In-GUI quantity step (PLAIN — price isn't known until the next step).
        IntConsumer onConfirm = qty -> promptPriceThenCreate(player, isBuy, qty);
        Runnable onCancel = () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));

        plugin.getServer().getScheduler().runTask(plugin, () ->
            new QuantitySelectMenu(
                Component.text((isBuy ? "Buy " : "Sell ") + selectedItem.getType().name().replace('_', ' '), GRAY),
                selectedItem.clone(), QuantitySelectMenu.Mode.PLAIN,
                1, maxQty, 1, 0, config.getCurrencySymbol(), onConfirm, onCancel)
            .open(player));
    }

    /**
     * Asks for the price per unit in chat (item-name search and price entry stay
     * chat-based by design), then creates the listing. {@code qty} is supplied by
     * the in-GUI quantity selector.
     */
    private void promptPriceThenCreate(Player player, boolean isBuy, int qty) {
        CustomEconomy plugin = CustomEconomy.getInstance();
        String sym = config.getCurrencySymbol();

        // Show the exploit-safe bound directly in the prompt.
        String boundHint = "";
        if (isBuy) {
            double ceiling = shopService.getBuyPriceCeiling(selectedItem.getType());
            if (ceiling < Double.MAX_VALUE) {
                boundHint = " §7(max " + FormatUtil.formatMoney(ceiling, sym) + ")";
            }
        } else {
            double floor = shopService.getSellPriceFloor(selectedItem.getType());
            if (floor > 0) {
                boundHint = " §7(min " + FormatUtil.formatMoney(floor, sym) + ")";
            }
        }

        chatInput.prompt(player,
            "§eEnter price per unit (" + sym + "):" + boundHint,
            priceInput -> {
                double price;
                try {
                    price = Double.parseDouble(priceInput.trim());
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid price.", RED)
                        .decoration(TextDecoration.ITALIC, false));
                    plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
                    return;
                }

                if (price <= 0) {
                    player.sendMessage(Component.text("Price must be greater than zero.", RED)
                        .decoration(TextDecoration.ITALIC, false));
                    plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
                    return;
                }

                Result result = isBuy
                    ? shopService.createBuyOrder(player, selectedItem.getType(), qty, price)
                    : shopService.createSellListing(player, selectedItem, qty, price);

                sendResultMessage(player, result, isBuy, qty, price, sym);
                plugin.getServer().getScheduler().runTask(plugin, onBack);
            });
    }

    private void sendResultMessage(Player player, Result result, boolean isBuy,
                                    int qty, double price, String sym) {
        switch (result) {
            case SUCCESS -> player.sendMessage(Component.text(
                (isBuy ? "Buy order" : "Sell listing") + " created: "
                + qty + "x " + selectedItem.getType().name().replace('_', ' ')
                + " @ " + FormatUtil.formatMoney(price, sym) + " each.", GREEN)
                .decoration(TextDecoration.ITALIC, false));
            case INSUFFICIENT_FUNDS -> player.sendMessage(Component.text(
                "You can't afford the escrow for that buy order.", RED)
                .decoration(TextDecoration.ITALIC, false));
            case INSUFFICIENT_ITEMS -> player.sendMessage(Component.text(
                "You don't have enough of that item.", RED)
                .decoration(TextDecoration.ITALIC, false));
            case MAX_LISTINGS_REACHED -> {
                int cap = shopService.getMaxListings(player);
                String capText = cap == Integer.MAX_VALUE ? "" : " of " + cap;
                player.sendMessage(Component.text(
                    "You've reached your maximum" + capText + " active listings.", RED)
                    .decoration(TextDecoration.ITALIC, false));
            }
            case INVALID_PRICE -> player.sendMessage(Component.text(
                "Invalid price.", RED).decoration(TextDecoration.ITALIC, false));
            case PRICE_TOO_LOW -> player.sendMessage(Component.text(
                "Price too low — minimum is "
                + FormatUtil.formatMoney(shopService.getSellPriceFloor(selectedItem.getType()), sym) + ".", RED)
                .decoration(TextDecoration.ITALIC, false));
            case PRICE_TOO_HIGH -> player.sendMessage(Component.text(
                "Price too high — maximum is "
                + FormatUtil.formatMoney(shopService.getBuyPriceCeiling(selectedItem.getType()), sym) + ".", RED)
                .decoration(TextDecoration.ITALIC, false));
            default -> player.sendMessage(Component.text(
                "Something went wrong creating your listing.", RED)
                .decoration(TextDecoration.ITALIC, false));
        }
    }

    // ── Icon builders ─────────────────────────────────────────────────────────

    private ItemStack makeSearchIcon() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("Search Item", GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  For BUY orders: type the item name", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  e.g. stone_bricks, oak_log", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("  For SELL offers: place the item", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  in the center slot directly.", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePlaceholder() {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("No item selected", GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  SELL: place item here from cursor", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  BUY:  use the name tag to search", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePreview() {
        ItemStack preview = selectedItem.clone();
        ItemMeta meta     = preview.getItemMeta();
        String name       = selectedItem.getType().name().replace('_', ' ');
        meta.displayName(Component.text(name, GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  Selected item", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  Click SELL or BUY to continue.", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        preview.setItemMeta(meta);
        preview.setAmount(1);
        return preview;
    }

    private ItemStack makeSellButton() {
        ItemStack item = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("Create Sell Offer", RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  Items removed from inventory now.", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  Returned on cancel or expiry.", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeBuyButton() {
        ItemStack item = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("Create Buy Order", GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  Money deducted upfront (escrow).", GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("  Refunded on cancel or expiry.", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeBack() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("Cancel", RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeGlass(TextColor color) {
        return GuiUtil.glass(color == RED ? Material.RED_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
    }

    private int countInInventory(Player player, Material mat) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == mat) count += stack.getAmount();
        }
        return count;
    }
}
