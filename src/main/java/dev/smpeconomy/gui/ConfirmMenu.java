package dev.smpeconomy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Yes/No confirmation dialog (27 slots).
 *
 * Confirm (lime pane) at 11, the subject item at 13 (with extra detail lore), and
 * Cancel (red pane) at 15 — a balanced centred row. Pure UI: the caller supplies
 * the two runnables.
 */
public final class ConfirmMenu extends BaseGui {

    private final ItemStack subject;
    private final List<Component> detailLore;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmMenu(Component title, ItemStack subject, List<Component> detailLore,
                       Runnable onConfirm, Runnable onCancel) {
        super(27, title.decoration(TextDecoration.BOLD, true));
        this.subject    = subject.clone();
        this.detailLore = detailLore;
        this.onConfirm  = onConfirm;
        this.onCancel   = onCancel;
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        ItemStack glass = GuiUtil.glass();
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, glass);

        inventory.setItem(13, makeSubject());

        inventory.setItem(11, pane(Material.LIME_STAINED_GLASS_PANE, "Confirm", GuiUtil.GREEN));
        onClick(11, e -> {
            ((Player) e.getWhoClicked()).closeInventory();
            try { onConfirm.run(); } catch (Throwable ignored) {}
        });

        inventory.setItem(15, pane(Material.RED_STAINED_GLASS_PANE, "Cancel", GuiUtil.RED));
        onClick(15, e -> {
            ((Player) e.getWhoClicked()).closeInventory();
            try { onCancel.run(); } catch (Throwable ignored) {}
        });
    }

    private ItemStack makeSubject() {
        ItemStack icon = subject.clone();
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        if (detailLore != null && !detailLore.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.addAll(detailLore);
            meta.lore(lore);
        }
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack pane(Material mat, String label, net.kyori.adventure.text.format.TextColor color) {
        ItemStack pane = new ItemStack(mat);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(GuiUtil.line(label, color));
        pane.setItemMeta(meta);
        return pane;
    }
}
