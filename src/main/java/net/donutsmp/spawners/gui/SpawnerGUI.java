
package net.donutsmp.spawners.gui;

import net.kyori.adventure.text.Component;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.SpawnerKeys;

import net.donutsmp.spawners.DonutSpawners;
import net.donutsmp.spawners.holder.SpawnerGUIHolder;
import net.donutsmp.spawners.storage.SpawnerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SpawnerGUI {
    private final DonutSpawners plugin;
    private final SpawnerData data;
    private final Inventory inventory;
    private final boolean isStorage;
    private final int page;
    private final int totalPages;

    public SpawnerGUI(DonutSpawners plugin, SpawnerData data, boolean isStorage) {
        this(plugin, data, isStorage, 1);
    }

    public SpawnerGUI(DonutSpawners plugin, SpawnerData data, boolean isStorage, int page) {
        this.plugin = plugin;
        this.data = data;
        this.isStorage = isStorage;
        this.page = page;
        int totalStacks = 0;
        if (isStorage) {
            for (Map.Entry<Material, Long> entry : data.getAccumulatedDrops().entrySet()) {
                totalStacks += (int) Math.ceil(entry.getValue() / 64.0);
            }
        }
        int calcPages = isStorage ? (int) Math.ceil(totalStacks / 45.0) : 1;
        this.totalPages = calcPages == 0 ? 1 : calcPages;

        Component title = isStorage
                ? plugin.messages().get(SpawnerKeys.GUI_TITLE_STORAGE, TokenBag.of()
                        .put("stack", data.getStackSize())
                        .put("type", capitalize(data.getType().name()))
                        .put("page", page)
                        .put("pages", this.totalPages))
                : plugin.messages().get(SpawnerKeys.GUI_TITLE, TokenBag.of()
                        .put("stack", data.getStackSize())
                        .put("type", capitalize(data.getType().name())));

        this.inventory = Bukkit.createInventory(new SpawnerGUIHolder(data, isStorage), isStorage ? 54 : 27, title);
        setupGUI();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private void setupGUI() {
        if (isStorage) {
            setupStorage();
        } else {
            setupMain();
        }
    }
    private void setupMain() {
        ItemStack skull = new ItemStack(data.getType().getHeadMaterial());
        ItemMeta skullMeta = skull.getItemMeta();
        skullMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_SPAWNER_ITEM_NAME, TokenBag.of()
                .put("stack", data.getStackSize())
                .put("type", data.getType().getDisplayName().toUpperCase(Locale.ENGLISH))));

        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().lore(SpawnerKeys.GUI_CLICK_TO_SELL));

        long storedItems = data.getAccumulatedDrops().values().stream().mapToLong(Long::longValue).sum();
        long storageCapacity = Math.max(1L, plugin.getConfig().getLong("settings.storage_display_capacity", 1_000_000L));
        double fillPercent = Math.min(100.0D, (storedItems * 100.0D) / storageCapacity);
        lore.add(plugin.messages().lore(SpawnerKeys.GUI_STORAGE_FILLED, TokenBag.of()
                .put("percent", String.format(Locale.ENGLISH, "%.1f", fillPercent))));

        skullMeta.lore(lore);
        skull.setItemMeta(skullMeta);
        inventory.setItem(13, skull);

        ItemStack storageItem = new ItemStack(Material.CHEST);
        ItemMeta storageMeta = storageItem.getItemMeta();
        storageMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_OPEN_STORAGE));
        List<Component> storageLore = new ArrayList<>();
        data.getAccumulatedDrops().entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.<Material, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(2)
                .forEach(entry -> storageLore.add(plugin.messages().lore(
                        SpawnerKeys.GUI_STORAGE_PREVIEW_ENTRY, TokenBag.of()
                                .put("amount", formatCompactAmount(entry.getValue()))
                                .put("item", capitalizeWords(entry.getKey().name())))));

        if (storageLore.isEmpty()) {
            storageLore.add(plugin.messages().lore(SpawnerKeys.GUI_STORAGE_PREVIEW_EMPTY));
        }
        storageMeta.lore(storageLore);
        storageItem.setItemMeta(storageMeta);
        inventory.setItem(11, storageItem);

        ItemStack xpBottle = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta xpMeta = xpBottle.getItemMeta();
        xpMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_COLLECT_XP));
        List<Component> xpLore = new ArrayList<>();
        xpLore.add(plugin.messages().lore(SpawnerKeys.GUI_COLLECT_XP_POINTS, TokenBag.of()
                        .put("xp", formatCompactAmountWithDecimal(data.getAccumulatedXP()))));
        xpMeta.lore(xpLore);
        xpBottle.setItemMeta(xpMeta);
        inventory.setItem(15, xpBottle);
    }


    private void setupStorage() {
        NamespacedKey pageKey = new NamespacedKey(plugin.bukkit(), "gui_page");
        NamespacedKey targetPageKey = new NamespacedKey(plugin.bukkit(), "gui_target_page");

        ItemStack backItem = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = backItem.getItemMeta();
        List<Component> backLore = new ArrayList<>();
        backMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_BACK));
        backLore.add(plugin.messages().lore(SpawnerKeys.GUI_BACK_LORE));
        backMeta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page);
        backMeta.lore(backLore);
        backItem.setItemMeta(backMeta);
        inventory.setItem(45, backItem);

        if (page > 1) {
            ItemStack previousArrow = new ItemStack(Material.ARROW);
            ItemMeta previousMeta = previousArrow.getItemMeta();
            previousMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_PREVIOUS_PAGE));
            List<Component> previousLore = new ArrayList<>();
            previousLore.add(plugin.messages().lore(SpawnerKeys.GUI_PREVIOUS_PAGE_LORE));
            previousMeta.lore(previousLore);
            previousMeta.getPersistentDataContainer().set(targetPageKey, PersistentDataType.INTEGER, page - 1);
            previousArrow.setItemMeta(previousMeta);
            inventory.setItem(48, previousArrow);
        }

        ItemStack spawnerCollectItem = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta spawnerCollectMeta = spawnerCollectItem.getItemMeta();
        spawnerCollectMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_STORAGE_COLLECT));
        List<Component> spawnerCollectLore = new ArrayList<>();
        spawnerCollectLore.add(plugin.messages().lore(SpawnerKeys.GUI_STORAGE_COLLECT_LORE));
        spawnerCollectMeta.lore(spawnerCollectLore);
        spawnerCollectItem.setItemMeta(spawnerCollectMeta);
        inventory.setItem(49, spawnerCollectItem);

        if (page < totalPages) {
            ItemStack nextArrow = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextArrow.getItemMeta();
            nextMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_NEXT_PAGE));
            List<Component> nextLore = new ArrayList<>();
            nextLore.add(plugin.messages().lore(SpawnerKeys.GUI_NEXT_PAGE_LORE));
            nextMeta.lore(nextLore);
            nextMeta.getPersistentDataContainer().set(targetPageKey, PersistentDataType.INTEGER, page + 1);
            nextArrow.setItemMeta(nextMeta);
            inventory.setItem(50, nextArrow);
        }

        ItemStack dropper = new ItemStack(Material.DROPPER);
        ItemMeta dropperMeta = dropper.getItemMeta();
        dropperMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_DROP_ALL));
        List<Component> dropLore = new ArrayList<>();
        dropLore.add(plugin.messages().lore(SpawnerKeys.GUI_DROP_ALL_LORE));
        dropperMeta.lore(dropLore);
        dropper.setItemMeta(dropperMeta);
        inventory.setItem(52, dropper);

        ItemStack goldIngot = new ItemStack(Material.GOLD_INGOT);
        ItemMeta goldMeta = goldIngot.getItemMeta();
        goldMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_SELL_ALL));
        List<Component> sellAllLore = new ArrayList<>();
        sellAllLore.add(plugin.messages().lore(SpawnerKeys.GUI_SELL_ALL_LORE));
        goldMeta.lore(sellAllLore);
        goldIngot.setItemMeta(goldMeta);
        inventory.setItem(53, goldIngot);

        int slot = 0;
        int startIndex = (page - 1) * 45;
        int endIndex = startIndex + 45;
        int index = 0;
        for (Map.Entry<Material, Long> entry : data.getAccumulatedDrops().entrySet()) {
            Material mat = entry.getKey();
            long totalAmount = entry.getValue();
            long remaining = totalAmount;
            while (remaining > 0 && index < endIndex) {
                if (index >= startIndex) {
                    if (slot >= 45) break;
                    int stackSize = (int) Math.min(remaining, 64);
                    ItemStack item = new ItemStack(mat, stackSize);
                    ItemMeta itemMeta = item.getItemMeta();
                    itemMeta.displayName(plugin.messages().lore(SpawnerKeys.GUI_STORAGE_ITEM_NAME, TokenBag.of()
                            .put("item", capitalize(mat.name()))
                            .put("amount", stackSize)));
                    item.setItemMeta(itemMeta);
                    inventory.setItem(slot, item);
                    slot++;
                }
                remaining -= 64;
                index++;
            }
            if (slot >= 45) break;
        }
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    private String formatCompactAmount(long amount) {
        if (amount >= 1_000_000) {
            return String.format(Locale.ENGLISH, "%.1fM", amount / 1_000_000.0);
        }
        if (amount >= 1_000) {
            return String.format(Locale.ENGLISH, "%.0fK", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }

    private String formatCompactAmountWithDecimal(long amount) {
        if (amount >= 1_000_000_000) {
            return String.format(Locale.ENGLISH, "%.1fB", amount / 1_000_000_000.0);
        }
        if (amount >= 1_000_000) {
            return String.format(Locale.ENGLISH, "%.1fM", amount / 1_000_000.0);
        }
        if (amount >= 1_000) {
            return String.format(Locale.ENGLISH, "%.1fK", amount / 1_000.0);
        }
        return String.valueOf(amount);
    }

    private String capitalizeWords(String input) {
        String[] parts = input.toLowerCase(Locale.ENGLISH).split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (i > 0) result.append(" ");
            result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return result.toString();
    }
}
