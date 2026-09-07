package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Reusable in-GUI quantity picker (27 slots), modelled on {@link BuyQuantityMenu}.
 *
 * This is pure UI: it knows nothing about the shop services. The caller supplies
 * an {@link IntConsumer} that receives the chosen quantity and performs the actual
 * transaction. That keeps it reusable for buying, selling/fulfilling, and the
 * new-offer quantity step.
 *
 * Layout (identical to BuyQuantityMenu):
 *   9/10/11  = -64/-10/-1 (red panes)
 *   13       = subject item (lore shows quantity + price/total by mode)
 *   15/16/17 = +1/+10/+64 (lime panes)
 *   21       = Cancel,  23 = Confirm
 */
public final class QuantitySelectMenu extends BaseGui {

    public enum Mode {
        /** Player pays — show cost, balance, and colour the total by affordability. */
        BUY_COST,
        /** Player earns — show payout in green, no money cap. */
        SELL_EARN,
        /** No price known yet (e.g. new-offer quantity) — quantity only. */
        PLAIN
    }

    /** GUI ceiling for "unbounded" quantities (e.g. buy orders): 36 stacks. */
    private static final int SOFT_CAP = 2304;

    private final ItemStack subjectIcon;
    private final Mode mode;
    private final int min;
    private final int effectiveMax;
    private final double unitPrice;
    private final String sym;
    private final IntConsumer onConfirm;
    private final Runnable onCancel;

    private int qty;

    public QuantitySelectMenu(Component title, ItemStack subjectIcon, Mode mode,
                              int min, int max, int initialQty, double unitPrice,
                              String currencySymbol, IntConsumer onConfirm, Runnable onCancel) {
        super(27, title.decoration(TextDecoration.BOLD, true));
        this.subjectIcon  = subjectIcon.clone();
        this.mode         = mode;
        this.min          = Math.max(1, min);
        this.effectiveMax = (max == Integer.MAX_VALUE) ? SOFT_CAP : Math.max(this.min, max);
        this.unitPrice    = unitPrice;
        this.sym          = currencySymbol;
        this.onConfirm    = onConfirm;
        this.onCancel     = onCancel;
        this.qty          = Math.max(this.min, Math.min(initialQty, this.effectiveMax));
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        ItemStack glass = GuiUtil.glass();
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, glass);

        inventory.setItem(13, makeCenter());

        // Decrement panes (9,10,11) → -64,-10,-1
        int[] dec = { 64, 10, 1 };
        int[] decSlots = { 9, 10, 11 };
        for (int i = 0; i < decSlots.length; i++) {
            int step = dec[i];
            if (qty - step >= min) {
                inventory.setItem(decSlots[i], pane(Material.RED_STAINED_GLASS_PANE, "-" + step, GuiUtil.RED));
                onClick(decSlots[i], e -> {
                    qty = Math.max(min, qty - step);
                    playTick((Player) e.getWhoClicked());
                    populate();
                });
            }
        }

        // Increment panes (15,16,17) → +1,+10,+64
        int[] inc = { 1, 10, 64 };
        int[] incSlots = { 15, 16, 17 };
        for (int i = 0; i < incSlots.length; i++) {
            int step = inc[i];
            if (qty + step <= effectiveMax) {
                inventory.setItem(incSlots[i], pane(Material.LIME_STAINED_GLASS_PANE, "+" + step, GuiUtil.GREEN));
                onClick(incSlots[i], e -> {
                    qty = Math.min(effectiveMax, qty + step);
                    playTick((Player) e.getWhoClicked());
                    populate();
                });
            }
        }

        // Cancel (21) / Confirm (23)
        inventory.setItem(21, pane(Material.RED_STAINED_GLASS_PANE, "Cancel", GuiUtil.RED));
        onClick(21, e -> {
            ((Player) e.getWhoClicked()).closeInventory();
            try { onCancel.run(); } catch (Throwable ignored) {}
        });

        inventory.setItem(23, pane(Material.LIME_STAINED_GLASS_PANE, "Confirm", GuiUtil.GREEN));
        onClick(23, e -> {
            ((Player) e.getWhoClicked()).closeInventory();
            try { onConfirm.accept(qty); } catch (Throwable ignored) {}
        });
    }

    private ItemStack makeCenter() {
        ItemStack icon = subjectIcon.clone();
        icon.setAmount(Math.max(1, Math.min(64, qty)));
        ItemMeta meta = icon.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(GuiUtil.line("  Quantity: " + qty, GuiUtil.GREEN));

        if (mode == Mode.BUY_COST) {
            double total = unitPrice * qty;
            double balance = viewer != null
                    ? CustomEconomy.getInstance().getVaultHook().getBalance(viewer) : 0;
            boolean affordable = total <= balance;
            lore.add(GuiUtil.line("  Unit price: " + FormatUtil.formatMoney(unitPrice, sym), GuiUtil.GRAY));
            lore.add(GuiUtil.line("  Total: " + FormatUtil.formatMoney(total, sym),
                    affordable ? GuiUtil.GREEN : GuiUtil.RED));
            lore.add(Component.empty());
            lore.add(GuiUtil.line("  Your balance: " + FormatUtil.formatMoney(balance, sym), GuiUtil.GRAY));
            if (!affordable) {
                lore.add(GuiUtil.line("  You can't afford this amount.", GuiUtil.RED));
            }
        } else if (mode == Mode.SELL_EARN) {
            double total = unitPrice * qty;
            lore.add(GuiUtil.line("  Price: " + FormatUtil.formatMoney(unitPrice, sym) + " each", GuiUtil.GRAY));
            lore.add(GuiUtil.line("  You earn: " + FormatUtil.formatMoney(total, sym), GuiUtil.GREEN));
        }

        lore.add(Component.empty());
        lore.add(GuiUtil.line("  Click Confirm to continue.", GuiUtil.GRAY));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack pane(Material mat, String label, TextColor color) {
        ItemStack pane = new ItemStack(mat);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(GuiUtil.line(label, color));
        pane.setItemMeta(meta);
        return pane;
    }

    private void playTick(Player player) {
        try { player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.0f, 0.5f); }
        catch (Throwable ignored) {}
    }
}
