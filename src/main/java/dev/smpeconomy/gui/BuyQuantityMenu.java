package dev.smpeconomy.gui;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
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
 * Quantity selector for shop purchases.
 *
 * {@code dynamicUnitPrice} is locked at the moment the player opens this menu
 * (computed by MarketService in ShopCategoryMenu).  The price shown and charged
 * will not shift if the market ticks while the menu is open — fair to the player.
 */
public final class BuyQuantityMenu extends BaseGui {

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor RED   = GuiUtil.RED;

    private final ShopEntry entry;
    private final double dynamicUnitPrice;
    private final ConfigManager config;
    private final ShopService shopService;
    private final Runnable onCancel;
    private int qty;

    public BuyQuantityMenu(ShopEntry entry, int initialQty, double dynamicUnitPrice,
                            ConfigManager config, ShopService shopService, Runnable onCancel) {
        super(27, CoreKeys.SHOP_BUY_TITLE, TokenBag.of().put("item", entry.displayName()));
        this.entry            = entry;
        this.dynamicUnitPrice = dynamicUnitPrice;
        this.qty              = Math.max(1, initialQty);
        this.config           = config;
        this.shopService      = shopService;
        this.onCancel         = onCancel;
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        ItemStack glass = makeGlass(GRAY);
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, glass);

        inventory.setItem(13, makeCenter());

        int[] decrements = { 64, 10, 1 };
        int[] decSlots   = { 9, 10, 11 };
        for (int i = 0; i < decSlots.length; i++) {
            int dec  = decrements[i];
            int slot = decSlots[i];
            if (qty - dec >= 1) {
                ItemStack pane = makeGlass(RED);
                ItemMeta m = pane.getItemMeta();
                m.displayName(messages.lore(CoreKeys.SHOP_BUY_DECREASE, TokenBag.of().put("amount", dec)));
                pane.setItemMeta(m);
                inventory.setItem(slot, pane);
                int step = dec;
                onClick(slot, e -> {
                    qty = Math.max(1, qty - step);
                    playTick((Player) e.getWhoClicked());
                    populate();
                });
            }
        }

        int[] increments = { 1, 10, 64 };
        int[] incSlots   = { 15, 16, 17 };
        for (int i = 0; i < incSlots.length; i++) {
            int inc  = increments[i];
            int slot = incSlots[i];
            ItemStack pane = makeGlass(GREEN);
            ItemMeta m = pane.getItemMeta();
            m.displayName(messages.lore(CoreKeys.SHOP_BUY_INCREASE, TokenBag.of().put("amount", inc)));
            pane.setItemMeta(m);
            inventory.setItem(slot, pane);
            int step = inc;
            onClick(slot, e -> {
                qty = Math.min(64, qty + step);
                playTick((Player) e.getWhoClicked());
                populate();
            });
        }

        ItemStack cancel = makeGlass(RED);
        ItemMeta cm = cancel.getItemMeta();
        cm.displayName(messages.lore(CoreKeys.SHOP_BUY_CANCEL));
        cancel.setItemMeta(cm);
        inventory.setItem(21, cancel);
        onClick(21, e -> {
            Player p = (Player) e.getWhoClicked();
            p.closeInventory();
            try { onCancel.run(); } catch (Throwable ignored) {}
        });

        ItemStack confirm = makeGlass(GREEN);
        ItemMeta fm = confirm.getItemMeta();
        fm.displayName(messages.lore(CoreKeys.SHOP_BUY_CONFIRM));
        confirm.setItemMeta(fm);
        inventory.setItem(23, confirm);
        onClick(23, this::handleConfirm);
    }

    private void handleConfirm(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        double cost = dynamicUnitPrice * qty;
        Runnable doPurchase = () -> runPurchase(player, cost);

        // Guard expensive purchases with a Yes/No confirmation.
        if (cost > config.getShopConfirmAbove()) {
            CustomEconomy plugin = CustomEconomy.getInstance();
            String sym = config.getCurrencySymbol();
            ItemStack subject = new ItemStack(entry.material());
            plugin.getServer().getScheduler().runTask(plugin, () -> new ConfirmMenu(
                GuiUtil.title("Confirm Purchase", GOLD),
                subject,
                List.of(GuiUtil.line("  Buy " + qty + "x " + entry.displayName()),
                        GuiUtil.line("  Total: " + FormatUtil.formatMoney(cost, sym), GREEN)),
                doPurchase,
                () -> plugin.getServer().getScheduler().runTask(plugin, () -> this.open(player))
            ).open(player));
        } else {
            doPurchase.run();
        }
    }

    private void runPurchase(Player player, double cost) {
        String sym = config.getCurrencySymbol();
        ShopService.BuyResult result = shopService.buyDirect(player, entry.material(), qty, dynamicUnitPrice);
        switch (result) {
            case SUCCESS -> messages.send(player, CoreKeys.SHOP_BUY_PURCHASED, TokenBag.of().put("quantity", qty).put("item", entry.displayName()).put("cost", FormatUtil.formatMoney(cost, sym)));
            case PARTIAL_SUCCESS -> messages.send(player, CoreKeys.SHOP_BUY_PURCHASED_PARTIAL, TokenBag.of().put("quantity", qty).put("item", entry.displayName()).put("cost", FormatUtil.formatMoney(cost, sym)));
            case INSUFFICIENT_FUNDS -> messages.send(player, CoreKeys.SHOP_BUY_CANNOT_AFFORD);
            case NO_INVENTORY_SPACE -> messages.send(player, CoreKeys.SHOP_BUY_INVENTORY_FULL);
            default -> {}
        }
    }

    private ItemStack makeCenter() {
        ItemStack icon = new ItemStack(entry.material());
        ItemMeta meta  = icon.getItemMeta();
        meta.displayName(messages.lore(CoreKeys.SHOP_BUY_ITEM_NAME, TokenBag.of().put("item", entry.displayName())));

        String sym = config.getCurrencySymbol();
        double total   = dynamicUnitPrice * qty;
        double balance = viewer != null
                ? CustomEconomy.getInstance().getVaultHook().getBalance(viewer) : 0;
        boolean affordable = total <= balance;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.SHOP_BUY_QUANTITY, TokenBag.of().put("quantity", qty)));
        lore.add(messages.lore(CoreKeys.SHOP_BUY_UNIT_PRICE, TokenBag.of().put("price", FormatUtil.formatMoney(dynamicUnitPrice, sym))));
        lore.add(messages.lore(affordable ? CoreKeys.SHOP_BUY_TOTAL_AFFORDABLE
                                          : CoreKeys.SHOP_BUY_TOTAL_UNAFFORDABLE, TokenBag.of().put("total", FormatUtil.formatMoney(total, sym))));
        lore.add(messages.lore(CoreKeys.SHOP_BUY_BALANCE, TokenBag.of().put("balance", FormatUtil.formatMoney(balance, sym))));
        lore.add(Component.empty());
        lore.add(messages.lore(CoreKeys.SHOP_BUY_CLICK_TO_CONFIRM));

        meta.lore(lore);
        icon.setItemMeta(meta);
        icon.setAmount(Math.min(64, qty));
        return icon;
    }

    private ItemStack makeGlass(TextColor color) {
        Material mat = Material.GRAY_STAINED_GLASS_PANE;
        if (color == GREEN) mat = Material.LIME_STAINED_GLASS_PANE;
        if (color == RED)   mat = Material.RED_STAINED_GLASS_PANE;
        return GuiUtil.glass(mat);
    }

    private void playTick(Player player) {
        try { player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.5f); }
        catch (Throwable ignored) {}
    }
}
