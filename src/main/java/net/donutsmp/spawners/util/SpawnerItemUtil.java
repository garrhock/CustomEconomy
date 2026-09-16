package net.donutsmp.spawners.util;

import net.donutsmp.spawners.mob.SpawnerType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class SpawnerItemUtil {

    public static ItemStack createSpawnerItem(SpawnerType type, int stack) {
        ItemStack item = new ItemStack(Material.SPAWNER, stack);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&a" + type.getDisplayName() + " Spawner"));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7Type: " + type.getDisplayName()));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static SpawnerType getSpawnerTypeFromItem(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();

        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (line.startsWith(ChatColor.GRAY + "Type: ")) {
                    SpawnerType type =
                        SpawnerType.fromString(line.substring((ChatColor.GRAY + "Type: ").length()));
                    if (type != null) return type;
                }
            }
        }

        // Spawners from elsewhere (SilkSpawners, silk-touch mining, /give) store their mob in
        // the block state instead of lore. Without this they'd place as empty vanilla spawners.
        if (meta instanceof BlockStateMeta blockMeta && blockMeta.hasBlockState()
                && blockMeta.getBlockState() instanceof CreatureSpawner spawner) {
            return SpawnerType.fromEntityType(spawner.getSpawnedType());
        }
        return null;
    }

    public static int getSpawnerStackFromItem(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER || !item.hasItemMeta()) return 1;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) return 1;
        List<String> lore = meta.getLore();
        for (String line : lore) {
            if (line.startsWith(ChatColor.GRAY + "Stack: ")) {
                try {
                    return Integer.parseInt(line.substring((ChatColor.GRAY + "Stack: ").length()));
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }
}
