package dev.smpeconomy.gui;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.repository.PlayerShopRepository.SortOrder;
import dev.smpeconomy.model.PlayerListing;
import dev.smpeconomy.model.PlayerListing.Type;
import dev.smpeconomy.service.ChatInputService;
import dev.smpeconomy.service.PlayerShopService;
import dev.smpeconomy.service.PlayerShopService.Result;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Main player-shop browser — 6-row chest.
 *
 * Header (row 1, slots 0-8):
 *   0=filler  1=filter  2-3=filler  4=toggle  5-6=filler  7=myOffers  8=newOffer
 * Content (rows 2-5, slots 9-44): 36 listings per page
 * Footer (row 6, slots 45-53):
 *   45=back  46-47=filler  48=prev  49=page  50=next  51-53=filler
 */
public final class PlayerShopMenu extends BaseGui {

    // Header slots: [MyOffers][_][_][Toggle][NewOffer][_][_][Filter]
    private static final int SLOT_MY_OFFERS = 3;
    private static final int SLOT_TOGGLE    = 4;
    private static final int SLOT_NEW_OFFER = 5;
    private static final int SLOT_FILTER    = 8;
    // Footer slots
    private static final int SLOT_BACK      = 45;
    private static final int SLOT_PREV      = 48;
    private static final int SLOT_PAGE      = 49;
    private static final int SLOT_NEXT      = 50;
    // Loading placeholder lives in the content area (not the page slot) to avoid the page-icon flash.
    private static final int SLOT_LOADING   = 22;

    private static final int ITEMS_PER_PAGE = 36;

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor RED   = GuiUtil.RED;
    private static final TextColor AQUA  = GuiUtil.AQUA;

    private final ConfigManager config;
    private final PlayerShopService shopService;

    private Type viewing  = Type.SELL;
    private SortOrder sort = SortOrder.NEWEST;
    private int page       = 0;

    public PlayerShopMenu(ConfigManager config, PlayerShopService shopService) {
        this(config, shopService, Type.SELL);
    }

    /** Opens on a specific view: {@code Type.SELL} for the auction house, {@code Type.BUY} for offers. */
    public PlayerShopMenu(ConfigManager config, PlayerShopService shopService, Type initialView) {
        super(54, CoreKeys.PLAYERSHOP_MENU_TITLE);
        this.config      = config;
        this.shopService = shopService;
        this.viewing     = initialView;
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        Player player = viewer;
        if (player == null) return;

        // Glass only frames the header (row 1) and footer (row 6); content stays empty.
        ItemStack glass = makeGlass();
        for (int i = 0; i < 9; i++)   inventory.setItem(i, glass);
        for (int i = 45; i < 54; i++) inventory.setItem(i, glass);

        // ── Header ────────────────────────────────────────────────────────────
        inventory.setItem(SLOT_FILTER, makeFilterIcon());
        onClick(SLOT_FILTER, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new PlayerShopFilterMenu(sort, chosen -> {
                    sort = chosen;
                    page = 0;
                    plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
                }, () -> this.open(player)).open(player));
        });

        inventory.setItem(SLOT_TOGGLE, makeToggle());
        onClick(SLOT_TOGGLE, e -> {
            viewing = (viewing == Type.SELL) ? Type.BUY : Type.SELL;
            page = 0;
            populate();
        });

        inventory.setItem(SLOT_MY_OFFERS, makeMyOffersIcon());
        onClick(SLOT_MY_OFFERS, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new PlayerShopMyOffersMenu(config, shopService,
                    () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player)))
                .open(player));
        });

        inventory.setItem(SLOT_NEW_OFFER, makeNewOfferIcon());
        onClick(SLOT_NEW_OFFER, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            ChatInputService chat = plugin.getChatInputService();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new PlayerShopNewOfferMenu(config, shopService, chat,
                    () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player)))
                .open(player));
        });

        // ── Footer (static chrome) ────────────────────────────────────────────
        inventory.setItem(SLOT_BACK, makeNav(Material.RED_STAINED_GLASS_PANE, "Back to Shop", RED));
        onClick(SLOT_BACK, e -> {
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, () ->
                new ShopMainMenu(config, plugin.getShopService(), plugin.getMarketService()).open(player));
        });
        inventory.setItem(SLOT_LOADING, makeLoading());

        // ── Listings (loaded off-thread) ──────────────────────────────────────
        final Type reqViewing = viewing;
        final var reqSort = sort;
        final int reqPage = page;
        asyncLoad(
            () -> shopService.getListings(reqViewing, reqSort, reqPage),
            listings -> {
                inventory.setItem(SLOT_LOADING, null);
                int listingCount = listings.size();
                for (int i = 0; i < listingCount && i < ITEMS_PER_PAGE; i++) {
                    int slot = 9 + i;
                    PlayerListing listing = listings.get(i);
                    inventory.setItem(slot, makeListingIcon(listing));
                    onClick(slot, e -> handleListingClick(player, listing));
                }

                boolean hasMore = listingCount == ITEMS_PER_PAGE;
                if (page > 0) {
                    inventory.setItem(SLOT_PREV, makeNav(Material.ARROW, "Previous Page", GOLD));
                    onClick(SLOT_PREV, e -> { page--; populate(); });
                }
                inventory.setItem(SLOT_PAGE, makePageIcon());
                if (hasMore) {
                    inventory.setItem(SLOT_NEXT, makeNav(Material.ARROW, "Next Page", GOLD));
                    onClick(SLOT_NEXT, e -> { page++; populate(); });
                }
            });
    }

    private ItemStack makeLoading() {
        return GuiUtil.loading();
    }

    // ── Listing interaction ───────────────────────────────────────────────────

    private void handleListingClick(Player player, PlayerListing listing) {
        if (listing.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(messages.get(CoreKeys.PLAYERSHOP_MENU_OWN_LISTING)
                .decoration(TextDecoration.ITALIC, false));
            return;
        }

        CustomEconomy plugin = CustomEconomy.getInstance();
        String sym     = config.getCurrencySymbol();
        int available  = listing.quantityRemaining();
        boolean isSell = listing.listingType() == Type.SELL;

        // Determine the upper bound and the quantity-menu mode.
        int max;
        QuantitySelectMenu.Mode mode;
        if (isSell) {                       // buying from a sell listing
            max  = available;
            mode = QuantitySelectMenu.Mode.BUY_COST;
        } else {                            // fulfilling a buy order
            Material mat = Material.matchMaterial(listing.itemKey());
            int inInv = (mat == null) ? 0 : countInInventory(player, mat);
            max  = Math.min(available, inInv);
            mode = QuantitySelectMenu.Mode.SELL_EARN;
        }

        if (max < 1) {
            messages.send(player, isSell ? CoreKeys.PLAYERSHOP_MENU_OUT_OF_STOCK
                                                  : CoreKeys.PLAYERSHOP_MENU_NOTHING_TO_SELL);
            return;
        }

        IntConsumer onConfirm = qty -> {
            Runnable doTxn = () -> {
                Result result = isSell
                    ? shopService.buyFromListing(player, listing.id(), qty)
                    : shopService.fulfillBuyOrder(player, listing.id(), qty);
                sendListingResult(player, result, isSell, qty, listing, sym);
                plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));
            };
            double total = listing.pricePerUnit() * qty;
            // Guard expensive purchases with a Yes/No confirmation.
            if (isSell && total > config.getShopConfirmAbove()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> new ConfirmMenu(
                    GuiUtil.title("Confirm Purchase", GOLD),
                    iconFromListing(listing),
                    List.of(GuiUtil.line("  Buy " + qty + "x " + listing.itemDisplayName()),
                            GuiUtil.line("  Total: " + FormatUtil.formatMoney(total, sym), GREEN)),
                    doTxn,
                    () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player))
                ).open(player));
            } else {
                doTxn.run();
            }
        };
        Runnable onCancel = () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player));

        plugin.getServer().getScheduler().runTask(plugin, () ->
            new QuantitySelectMenu(
                messages.get(isSell ? CoreKeys.PLAYERSHOP_MENU_QUANTITY_BUY_TITLE
                                             : CoreKeys.PLAYERSHOP_MENU_QUANTITY_SELL_TITLE, TokenBag.of().put("item", listing.itemDisplayName())),
                iconFromListing(listing), mode, 1, max, 1, listing.pricePerUnit(), sym,
                onConfirm, onCancel
            ).open(player));
    }

    private void sendListingResult(Player player, Result result, boolean isSell,
                                    int qty, PlayerListing listing, String sym) {
        double total = listing.pricePerUnit() * qty;
        switch (result) {
            case SUCCESS -> messages.send(player, isSell ? CoreKeys.PLAYERSHOP_MENU_PURCHASED : CoreKeys.PLAYERSHOP_MENU_SOLD, TokenBag.of().put("quantity", qty).put("item", listing.itemDisplayName()).put("total", FormatUtil.formatMoney(total, sym)));
            case INSUFFICIENT_FUNDS -> messages.send(player, CoreKeys.PLAYERSHOP_MENU_CANNOT_AFFORD);
            case INSUFFICIENT_ITEMS -> messages.send(player, CoreKeys.PLAYERSHOP_MENU_NOT_ENOUGH_ITEMS);
            case LISTING_UNAVAILABLE -> messages.send(player, CoreKeys.PLAYERSHOP_MENU_UNAVAILABLE);
            default -> messages.send(player, CoreKeys.PLAYERSHOP_MENU_FAILED, TokenBag.of().put("reason", result.name()));
        }
    }

    private ItemStack iconFromListing(PlayerListing listing) {
        try {
            ItemStack icon = ItemStack.deserializeBytes(listing.itemData()).clone();
            icon.setAmount(1);
            return icon;
        } catch (Exception e) {
            return new ItemStack(Material.BARRIER);
        }
    }

    private int countInInventory(Player player, Material mat) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == mat) count += stack.getAmount();
        }
        return count;
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
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_MENU_ITEM_NAME, TokenBag.of().put("item", listing.itemDisplayName())));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_MENU_SELLER, TokenBag.of().put("seller", listing.ownerName())));
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_MENU_PRICE, TokenBag.of().put("price", FormatUtil.formatMoney(listing.pricePerUnit(), sym))));

        if (listing.listingType() == Type.BUY) {
            lore.add(messages.lore(CoreKeys.PLAYERSHOP_MENU_FILLED, TokenBag.of().put("filled", listing.quantityFilled()).put("total", listing.quantityTotal())));
        } else {
            lore.add(messages.lore(CoreKeys.PLAYERSHOP_MENU_AVAILABLE, TokenBag.of().put("available", listing.quantityRemaining())));
        }
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_MENU_EXPIRES, TokenBag.of().put("time", GuiUtil.timeUntil(listing.expiresAt()))));

        lore.add(Component.empty());
        lore.add(messages.lore(listing.listingType() == Type.SELL
            ? CoreKeys.PLAYERSHOP_MENU_CLICK_TO_BUY
            : CoreKeys.PLAYERSHOP_MENU_CLICK_TO_FULFILL));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeToggle() {
        boolean isSell = viewing == Type.SELL;
        ItemStack item = new ItemStack(isSell ? Material.RED_CONCRETE : Material.LIME_CONCRETE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(isSell ? CoreKeys.PLAYERSHOP_MENU_TOGGLE_SELL
                                                    : CoreKeys.PLAYERSHOP_MENU_TOGGLE_BUY));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(isSell ? CoreKeys.PLAYERSHOP_MENU_TOGGLE_SELL_LORE
                                       : CoreKeys.PLAYERSHOP_MENU_TOGGLE_BUY_LORE),
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_MENU_CLICK_TO_SWITCH)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeFilterIcon() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_MENU_FILTER));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_MENU_FILTER_ACTIVE, TokenBag.of().put("sort", sortLabel(sort))),
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_MENU_FILTER_CLICK)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeMyOffersIcon() {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_MENU_MY_OFFERS));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_MENU_MY_OFFERS_LORE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeNewOfferIcon() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_MENU_NEW_OFFER));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_MENU_NEW_OFFER_LORE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePageIcon() {
        return GuiUtil.pageIcon(page + 1);
    }

    private ItemStack makeNav(Material mat, String label, TextColor color) {
        return GuiUtil.nav(mat, label, color);
    }

    private ItemStack makeGlass() {
        return GuiUtil.glass();
    }

    private String sortLabel(SortOrder s) {
        return switch (s) {
            case PRICE_HIGH   -> "Price: High → Low";
            case PRICE_LOW    -> "Price: Low → High";
            case QTY_HIGH     -> "Quantity: High → Low";
            case QTY_LOW      -> "Quantity: Low → High";
            case NEWEST       -> "Newest First";
            case OLDEST       -> "Oldest First";
            case ALPHABETICAL -> "A → Z";
        };
    }

}
