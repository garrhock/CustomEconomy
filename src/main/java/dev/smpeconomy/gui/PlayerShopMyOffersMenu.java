package dev.smpeconomy.gui;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.model.PlayerListing;
import dev.smpeconomy.model.PlayerListing.Type;
import dev.smpeconomy.service.PlayerShopService;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * My Offers — mirrors the Player Shop landing but shows only the viewer's listings.
 *
 * Header (row 1): All Offers(3)  Buy/Sell toggle(4)  Claim chest(5)  Create New Offer(6)
 * Content (rows 2-5): the viewer's listings
 * Footer (row 6): Back(45)  Prev(48)  Page(49)  Next(50)
 *
 * Active listings show the item icon (amount 1) with fill progress — click to cancel.
 * Completed listings that still have items waiting (fulfilled BUY, cancelled/expired
 * SELL) show the item icon WITH AN ENCHANT GLINT — click to claim those items. Once a
 * listing's claim items are gone it disappears from this page (it remains in history).
 */
public final class PlayerShopMyOffersMenu extends BaseGui {

    private static final int ITEMS_PER_PAGE = 36;
    private static final int SLOT_ALL_OFFERS = 3;
    private static final int SLOT_TOGGLE     = 4;
    private static final int SLOT_CLAIM      = 5;
    private static final int SLOT_NEW_OFFER  = 6;
    private static final int SLOT_BACK       = 45;
    private static final int SLOT_PREV       = 48;
    private static final int SLOT_PAGE       = 49;
    private static final int SLOT_NEXT       = 50;
    private static final int SLOT_LOADING    = 22;

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor RED   = GuiUtil.RED;
    private static final TextColor AQUA  = GuiUtil.AQUA;

    private final ConfigManager config;
    private final PlayerShopService shopService;
    private final Runnable onBack;

    private Type viewing = Type.SELL;
    private int page = 0;

    public PlayerShopMyOffersMenu(ConfigManager config, PlayerShopService shopService, Runnable onBack) {
        super(54, CoreKeys.PLAYERSHOP_OFFERS_TITLE);
        this.config      = config;
        this.shopService = shopService;
        this.onBack      = onBack;
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
        inventory.setItem(SLOT_ALL_OFFERS, makeAllOffersIcon());
        onClick(SLOT_ALL_OFFERS, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin,
                () -> new PlayerShopMenu(config, shopService).open(player));
        });

        inventory.setItem(SLOT_TOGGLE, makeToggle());
        onClick(SLOT_TOGGLE, e -> {
            viewing = (viewing == Type.SELL) ? Type.BUY : Type.SELL;
            page = 0;
            populate();
        });

        inventory.setItem(SLOT_CLAIM, makeClaimButton());
        onClick(SLOT_CLAIM, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new PlayerShopStorageMenu(shopService, () ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player)))
                .open(player));
        });

        inventory.setItem(SLOT_NEW_OFFER, makeNewOfferIcon());
        onClick(SLOT_NEW_OFFER, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            dev.smpeconomy.service.ChatInputService chat = plugin.getChatInputService();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new PlayerShopNewOfferMenu(config, shopService, chat,
                    () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player)))
                .open(player));
        });

        // ── Footer (static chrome) ────────────────────────────────────────────
        inventory.setItem(SLOT_BACK, makeNav(Material.RED_STAINED_GLASS_PANE, "Back", RED));
        onClick(SLOT_BACK, e -> {
            player.closeInventory();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });
        inventory.setItem(SLOT_LOADING, makeLoading());

        // ── Listings (loaded off-thread) ──────────────────────────────────────
        final java.util.UUID uuid = player.getUniqueId();
        final Type reqViewing = viewing;
        asyncLoad(
            () -> shopService.getMyListings(uuid, reqViewing).stream()
                // Visible if still active, or completed but with items left to claim.
                .filter(l -> l.isActive() || shopService.listingHasUnclaimedItems(l.id()))
                .toList(),
            visible -> {
                inventory.setItem(SLOT_LOADING, null);
                int totalPages = Math.max(1, (int) Math.ceil(visible.size() / (double) ITEMS_PER_PAGE));
                page = Math.max(0, Math.min(page, totalPages - 1));
                int start = page * ITEMS_PER_PAGE;
                int end   = Math.min(start + ITEMS_PER_PAGE, visible.size());

                for (int i = start; i < end; i++) {
                    int slot = 9 + (i - start);
                    PlayerListing listing = visible.get(i);
                    if (listing.isActive()) {
                        inventory.setItem(slot, makeListingIcon(listing));
                        onClick(slot, e -> openCancelConfirm(player, listing));
                    } else {
                        // Completed with items to claim — glinted item icon, click to claim.
                        inventory.setItem(slot, makeCompletedIcon(listing));
                        final long lid = listing.id();
                        onClick(slot, e -> {
                            shopService.claimListingStorage(player, lid);
                            player.sendMessage(messages.get(CoreKeys.PLAYERSHOP_OFFERS_CLAIMED)
                                .decoration(TextDecoration.ITALIC, false));
                            populate();
                        });
                    }
                }

                if (page > 0) {
                    inventory.setItem(SLOT_PREV, makeNav(Material.ARROW, "Previous Page", GOLD));
                    onClick(SLOT_PREV, e -> { page--; populate(); });
                }
                inventory.setItem(SLOT_PAGE, makePageIcon(page + 1, totalPages));
                if (page < totalPages - 1) {
                    inventory.setItem(SLOT_NEXT, makeNav(Material.ARROW, "Next Page", GOLD));
                    onClick(SLOT_NEXT, e -> { page++; populate(); });
                }
            });
    }

    private void openCancelConfirm(Player player, PlayerListing listing) {
        CustomEconomy plugin = CustomEconomy.getInstance();
        String sym = config.getCurrencySymbol();

        ItemStack subject;
        try {
            subject = ItemStack.deserializeBytes(listing.itemData()).clone();
            subject.setAmount(1);
        } catch (Exception e) {
            subject = new ItemStack(Material.BARRIER);
        }

        List<Component> detail = List.of(
            GuiUtil.line("Cancel your listing for"),
            GuiUtil.line("  " + listing.quantityRemaining() + "x " + listing.itemDisplayName(), GOLD),
            GuiUtil.line("  @ " + FormatUtil.formatMoney(listing.pricePerUnit(), sym) + " each"));

        Runnable onConfirm = () -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            PlayerShopService.Result r = shopService.cancelListing(player, listing.id());
            player.sendMessage(r == PlayerShopService.Result.SUCCESS
                ? GuiUtil.line("Listing cancelled.", GREEN)
                : GuiUtil.line("Could not cancel: " + r.name(), RED));
            this.open(player);
        });
        Runnable onCancel = () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));

        final ItemStack subjectIcon = subject;
        plugin.getServer().getScheduler().runTask(plugin, () ->
            new ConfirmMenu(GuiUtil.title("Cancel Listing?", RED), subjectIcon, detail, onConfirm, onCancel)
                .open(player));
    }

    // ── Icon builders ─────────────────────────────────────────────────────────

    private ItemStack makeListingIcon(PlayerListing listing) {
        ItemStack icon;
        try {
            icon = ItemStack.deserializeBytes(listing.itemData()).clone();
            icon.setAmount(1);
        } catch (Exception e) {
            icon = new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = icon.getItemMeta();
        String sym    = config.getCurrencySymbol();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_OFFERS_ITEM_NAME, TokenBag.of().put("item", listing.itemDisplayName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(messages.lore(listing.listingType() == Type.SELL
            ? CoreKeys.PLAYERSHOP_OFFERS_TYPE_SELL
            : CoreKeys.PLAYERSHOP_OFFERS_TYPE_BUY));
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_OFFERS_PRICE, TokenBag.of().put("price", FormatUtil.formatMoney(listing.pricePerUnit(), sym))));
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_OFFERS_FILLED, TokenBag.of().put("filled", listing.quantityFilled()).put("total", listing.quantityTotal())));
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_OFFERS_EXPIRES, TokenBag.of().put("time", GuiUtil.timeUntil(listing.expiresAt()))));
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_OFFERS_CLICK_CANCEL));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeCompletedIcon(PlayerListing listing) {
        ItemStack icon;
        try {
            icon = ItemStack.deserializeBytes(listing.itemData()).clone();
            icon.setAmount(1);
        } catch (Exception e) {
            icon = new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = icon.getItemMeta();
        String sym    = config.getCurrencySymbol();
        boolean bought = listing.listingType() == Type.BUY;
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_OFFERS_DONE_NAME, TokenBag.of().put("item", listing.itemDisplayName())));

        meta.lore(List.of(
            Component.empty(),
            messages.lore(bought ? CoreKeys.PLAYERSHOP_OFFERS_ORDER_FILLED
                                     : CoreKeys.PLAYERSHOP_OFFERS_LISTING_CLOSED),
            messages.lore(CoreKeys.PLAYERSHOP_OFFERS_PRICE, TokenBag.of().put("price", FormatUtil.formatMoney(listing.pricePerUnit(), sym))),
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_OFFERS_CLICK_CLAIM)
        ));
        // Enchant glint differentiates a completed order from an active one.
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeToggle() {
        boolean isSell = viewing == Type.SELL;
        ItemStack item = new ItemStack(isSell ? Material.RED_CONCRETE : Material.LIME_CONCRETE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(isSell ? CoreKeys.PLAYERSHOP_OFFERS_TOGGLE_SELL
                                                    : CoreKeys.PLAYERSHOP_OFFERS_TOGGLE_BUY));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(isSell ? CoreKeys.PLAYERSHOP_OFFERS_SHOWING_SELL
                                       : CoreKeys.PLAYERSHOP_OFFERS_SHOWING_BUY),
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_OFFERS_CLICK_SWITCH)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeAllOffersIcon() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text(messages.raw(CoreKeys.PLAYERSHOP_OFFERS_ALL), GOLD)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_OFFERS_ALL_LORE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeClaimButton() {
        ItemStack item = new ItemStack(Material.ENDER_CHEST);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text(messages.raw(CoreKeys.PLAYERSHOP_OFFERS_CLAIM_ITEMS), GOLD)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_OFFERS_CLAIM_ITEMS_LORE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeNewOfferIcon() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text(messages.raw(CoreKeys.PLAYERSHOP_OFFERS_NEW), GOLD)
            .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_OFFERS_NEW_LORE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeLoading() {
        return GuiUtil.loading();
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
}
