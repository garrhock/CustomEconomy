package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.model.SellResult;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class GuiManager implements Listener {

    private static final TextColor GREEN = TextColor.color(0x55FF55);

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof BaseGui gui) {
            gui.handleClick(event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellGui)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        ItemStack[] contents = event.getInventory().getContents();
        event.getInventory().clear();

        CustomEconomy plugin = CustomEconomy.getInstance();
        SellResult result = plugin.getSellService().sellExternal(player, contents);

        // Return unsellable items — drop overflow on the ground
        for (ItemStack item : result.getReturned()) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            overflow.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }

        if (result.isSuccess() && result.getTotalEarned() > 0) {
            String sym = plugin.getConfigManager().getCurrencySymbol();
            player.sendActionBar(
                Component.text("+" + FormatUtil.formatMoney(result.getTotalEarned(), sym), GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        }
    }
}
