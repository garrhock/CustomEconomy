package dev.smpeconomy.gui;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;

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
        super(27, CoreKeys.PLAYERSHOP_NEW_TITLE);
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
                    messages.get(CoreKeys.PLAYERSHOP_NEW_PLACE_ITEM_FIRST)
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
                    messages.get(CoreKeys.PLAYERSHOP_NEW_SEARCH_FIRST)
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
        chatInput.prompt(player, messages.raw(CoreKeys.PLAYERSHOP_NEW_PROMPT), input -> {
            Material mat = Material.matchMaterial(input.trim().toUpperCase()
                .replace(' ', '_').replace('-', '_'));
            if (mat == null || mat == Material.AIR) {
                messages.send(player, CoreKeys.PLAYERSHOP_NEW_UNKNOWN_ITEM, TokenBag.of().put("input", input));
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
            messages.send(player, CoreKeys.PLAYERSHOP_NEW_NONE_IN_INVENTORY, TokenBag.of().put("item", selectedItem.getType().name().replace('_', ' ')));
            plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
            return;
        }

        // In-GUI quantity step (PLAIN — price isn't known until the next step).
        IntConsumer onConfirm = qty -> promptPriceThenCreate(player, isBuy, qty);
        Runnable onCancel = () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));

        plugin.getServer().getScheduler().runTask(plugin, () ->
            new QuantitySelectMenu(
                messages.get(isBuy ? CoreKeys.PLAYERSHOP_NEW_QTY_BUY_TITLE
                                            : CoreKeys.PLAYERSHOP_NEW_QTY_SELL_TITLE, TokenBag.of().put("item", selectedItem.getType().name().replace('_', ' '))),
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
                    player.sendMessage(messages.get(CoreKeys.PLAYERSHOP_NEW_INVALID_PRICE)
                        .decoration(TextDecoration.ITALIC, false));
                    plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
                    return;
                }

                if (price <= 0) {
                    player.sendMessage(messages.get(CoreKeys.PLAYERSHOP_NEW_PRICE_POSITIVE)
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
            case SUCCESS -> messages.send(player, isBuy ? CoreKeys.PLAYERSHOP_NEW_BUY_CREATED : CoreKeys.PLAYERSHOP_NEW_SELL_CREATED, TokenBag.of().put("quantity", qty).put("item", selectedItem.getType().name().replace('_', ' ')).put("price", FormatUtil.formatMoney(price, sym)));
            case INSUFFICIENT_FUNDS -> messages.send(player, CoreKeys.PLAYERSHOP_NEW_NO_ESCROW);
            case INSUFFICIENT_ITEMS -> messages.send(player, CoreKeys.PLAYERSHOP_NEW_NOT_ENOUGH);
            case MAX_LISTINGS_REACHED -> {
                int cap = shopService.getMaxListings(player);
                if (cap == Integer.MAX_VALUE) {
                    messages.send(player, CoreKeys.PLAYERSHOP_NEW_MAX_LISTINGS);
                } else {
                    messages.send(player, CoreKeys.PLAYERSHOP_NEW_MAX_LISTINGS_CAPPED, TokenBag.of().put("cap", cap));
                }
            }
            case INVALID_PRICE -> messages.send(player, CoreKeys.PLAYERSHOP_NEW_INVALID_PRICE);
            case PRICE_TOO_LOW -> messages.send(player, CoreKeys.PLAYERSHOP_NEW_PRICE_TOO_LOW, TokenBag.of().put("minimum", FormatUtil.formatMoney(
                    shopService.getSellPriceFloor(selectedItem.getType()), sym)));
            case PRICE_TOO_HIGH -> messages.send(player, CoreKeys.PLAYERSHOP_NEW_PRICE_TOO_HIGH, TokenBag.of().put("maximum", FormatUtil.formatMoney(
                    shopService.getBuyPriceCeiling(selectedItem.getType()), sym)));
            default -> messages.send(player, CoreKeys.PLAYERSHOP_NEW_FAILED);
        }
    }

    // ── Icon builders ─────────────────────────────────────────────────────────

    private ItemStack makeSearchIcon() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_NEW_SEARCH_NAME));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SEARCH_LORE_1),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SEARCH_LORE_2),
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SEARCH_LORE_3),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SEARCH_LORE_4)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePlaceholder() {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_NEW_NO_ITEM));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_NO_ITEM_LORE_1),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_NO_ITEM_LORE_2)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePreview() {
        ItemStack preview = selectedItem.clone();
        ItemMeta meta     = preview.getItemMeta();
        String name       = selectedItem.getType().name().replace('_', ' ');
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_NEW_SELECTED_NAME, TokenBag.of().put("item", name)));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SELECTED_LORE_1),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SELECTED_LORE_2)
        ));
        preview.setItemMeta(meta);
        preview.setAmount(1);
        return preview;
    }

    private ItemStack makeSellButton() {
        ItemStack item = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_NEW_SELL_BUTTON));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SELL_LORE_1),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_SELL_LORE_2)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeBuyButton() {
        ItemStack item = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_NEW_BUY_BUTTON));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_BUY_LORE_1),
            messages.lore(CoreKeys.PLAYERSHOP_NEW_BUY_LORE_2)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeBack() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_NEW_CANCEL));
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
