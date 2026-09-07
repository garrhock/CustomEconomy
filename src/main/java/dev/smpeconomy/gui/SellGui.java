package dev.smpeconomy.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class SellGui implements InventoryHolder {

    private static final TextColor GRAY = NamedTextColor.GRAY;

    private final Inventory inventory;

    public SellGui() {
        this.inventory = Bukkit.createInventory(this, 54,
            Component.text("Drop items in to sell", GRAY)
                .decoration(TextDecoration.BOLD, true));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }
}
