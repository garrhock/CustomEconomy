package net.donutsmp.spawners.economy;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.api.CustomEconomyAPI;
import dev.smpeconomy.model.SellResult;
import dev.smpeconomy.model.Transaction;
import net.donutsmp.spawners.DonutSpawners;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Spawner drops go through CustomEconomy instead of being paid out directly, so they get the
 * same prices, market factors and multipliers as any other sale.
 */
public class EconomyHandler {

    private final DonutSpawners plugin;

    public EconomyHandler(DonutSpawners plugin) {
        this.plugin = plugin;
    }

    private CustomEconomyAPI api() {
        CustomEconomy economy = CustomEconomy.getInstance();
        return economy == null ? null : economy.getAPI();
    }

    /** Effective per-unit price for the player, or 0 if the item has no price. */
    public double getWorth(Material material, Player player) {
        CustomEconomyAPI api = api();
        if (api == null) return 0.0;
        double price = api.getSellPrice(new ItemStack(material), player);
        return price < 0 ? 0.0 : price;
    }

    // Sold entries are removed from the map, unpriced ones are left in so they stay in the
    // spawner. Don't clear the map after calling this.
    public double sellItems(Player player, Map<Material, Long> drops) {
        CustomEconomyAPI api = api();
        if (api == null) {
            plugin.getLogger().warning("CustomEconomy unavailable; spawner drops were not sold.");
            return 0.0;
        }

        double total = 0.0;
        Iterator<Map.Entry<Material, Long>> it = drops.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Material, Long> entry = it.next();
            Material material = entry.getKey();
            long amount = entry.getValue() == null ? 0L : entry.getValue();

            if (material == null || material.isAir() || amount <= 0) {
                it.remove();
                continue;
            }

            SellResult result = api.sellExternal(player, toStacks(material, amount),
                                                 Transaction.Source.SPAWNER);
            long sold = result.getQuantitySold();
            if (sold <= 0) continue;   // unsellable — leave it in the spawner

            total += result.getTotalEarned();
            long remaining = amount - sold;
            if (remaining > 0) entry.setValue(remaining);
            else it.remove();
        }
        return total;
    }

    private ItemStack[] toStacks(Material material, long amount) {
        int max = Math.max(1, material.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        long remaining = amount;
        while (remaining > 0) {
            int size = (int) Math.min(remaining, max);
            stacks.add(new ItemStack(material, size));
            remaining -= size;
        }
        return stacks.toArray(new ItemStack[0]);
    }
}
