package dev.smpeconomy.gui;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.model.StorageItem;
import dev.smpeconomy.service.PlayerShopService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Claim chest — 6-row inventory showing all unclaimed items in the player's storage.
 *
 * Layout:
 *   Rows 1-5 (slots 0-44): storage items, 45 per page
 *   Row 6 (slots 45-53):   back(45), claim-all(47), prev(48), page(49), next(50)
 */
public final class PlayerShopStorageMenu extends BaseGui {

    // Items fill rows 2-5 (slots 9-44); header row 1 + footer row 6 are glass.
    // Page size is governed by the repository (PAGE_SIZE) and service.countStoragePages.
    private static final int CONTENT_START  = 9;
    private static final int SLOT_CLAIM_ALL = 4;   // header centre
    private static final int SLOT_BACK      = 45;
    private static final int SLOT_PREV      = 48;
    private static final int SLOT_PAGE      = 49;
    private static final int SLOT_NEXT      = 50;
    private static final int SLOT_LOADING   = 22;

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor RED   = GuiUtil.RED;

    private final PlayerShopService shopService;
    private final Runnable onBack;
    private int page = 0;

    public PlayerShopStorageMenu(PlayerShopService shopService, Runnable onBack) {
        super(54, CoreKeys.PLAYERSHOP_STORAGE_TITLE);
        this.shopService = shopService;
        this.onBack      = onBack;
    }

    private record StoragePage(List<StorageItem> items, int totalPages) {}

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        Player player = viewer;
        if (player == null) return;

        // Glass frames the header (row 1) and footer (row 6); content stays empty.
        ItemStack glass = makeGlass();
        for (int s = 0; s < 9; s++)   inventory.setItem(s, glass);
        for (int s = 45; s < 54; s++) inventory.setItem(s, glass);

        inventory.setItem(SLOT_BACK, makeNav(Material.RED_STAINED_GLASS_PANE, "Back", RED));
        onClick(SLOT_BACK, e -> {
            player.closeInventory();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });

        inventory.setItem(SLOT_CLAIM_ALL, makeClaimAll());
        onClick(SLOT_CLAIM_ALL, e -> {
            shopService.claimAllStorage(player);
            messages.send(player, CoreKeys.PLAYERSHOP_STORAGE_ALL_CLAIMED);
            player.closeInventory();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });

        inventory.setItem(SLOT_LOADING, makeLoading());

        // Items + page count loaded off-thread
        final java.util.UUID uuid = player.getUniqueId();
        final int reqPage = page;
        asyncLoad(
            () -> new StoragePage(shopService.getStorage(uuid, reqPage),
                                  shopService.countStoragePages(uuid)),
            result -> {
                int totalPages = result.totalPages();
                page = Math.max(0, Math.min(page, totalPages - 1));

                // Clear the content area (rows 2-5) before painting this page.
                for (int s = CONTENT_START; s <= 44; s++) inventory.setItem(s, null);

                List<StorageItem> items = result.items();
                for (int i = 0; i < items.size(); i++) {
                    StorageItem stored = items.get(i);
                    try {
                        ItemStack display = ItemStack.deserializeBytes(stored.itemData()).clone();
                        display.setAmount(stored.quantity());
                        addLore(display);
                        inventory.setItem(CONTENT_START + i, display);
                        onClick(CONTENT_START + i, e -> {
                            shopService.claimStorageItem(player, stored);
                            populate();
                        });
                    } catch (Exception ignored) {}
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

    private ItemStack makeLoading() {
        return GuiUtil.loading();
    }

    private void addLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.hasLore() ? new java.util.ArrayList<>(meta.lore()) : new java.util.ArrayList<>();
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.PLAYERSHOP_STORAGE_CLICK_TO_CLAIM));
        meta.lore(lore);
        item.setItemMeta(meta);
    }


    private ItemStack makeClaimAll() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.PLAYERSHOP_STORAGE_CLAIM_ALL));
        meta.lore(List.of(
            Component.empty(),
            messages.lore(CoreKeys.PLAYERSHOP_STORAGE_CLAIM_ALL_LORE)
        ));
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
}
