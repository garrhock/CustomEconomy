package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.model.ItemCategory;
import dev.smpeconomy.service.MultiplierService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MultiCategoryMenu extends BaseGui {

    private static final TextColor GOLD = TextColor.color(0xFFAA00);
    private static final TextColor GREEN = TextColor.color(0x55FF55);
    private static final TextColor YELLOW = TextColor.color(0xFFFF55);
    private static final TextColor GRAY = NamedTextColor.GRAY;
    private static final TextColor WHITE = NamedTextColor.WHITE;
    private static final TextColor RED = NamedTextColor.RED;

    // 20 stages snaking through the 54-slot grid (icon at slot 1, back at slot 45)
    private static final int[] PATH_SLOTS = {
            10, 19, 28, 37, 38, 39, 30, 21, 12, 13, 14, 23, 32, 41, 42, 43, 34, 25, 16, 7
    };

    private static final Material[] CAT_ICONS = new Material[ItemCategory.values().length];
    static {
        CAT_ICONS[ItemCategory.CROPS.ordinal()] = Material.WHEAT;
        CAT_ICONS[ItemCategory.ORES.ordinal()] = Material.DIAMOND_ORE;
        CAT_ICONS[ItemCategory.MOB_DROPS.ordinal()] = Material.ROTTEN_FLESH;
        CAT_ICONS[ItemCategory.NATURAL_ITEMS.ordinal()] = Material.OAK_LOG;
        CAT_ICONS[ItemCategory.ARMOR_AND_TOOLS.ordinal()] = Material.IRON_SWORD;
        CAT_ICONS[ItemCategory.FISH.ordinal()] = Material.COD;
        CAT_ICONS[ItemCategory.ENCHANTED_BOOKS.ordinal()] = Material.ENCHANTED_BOOK;
        CAT_ICONS[ItemCategory.POTIONS.ordinal()] = Material.POTION;
        CAT_ICONS[ItemCategory.BLOCKS.ordinal()] = Material.STONE;
    }

    private final MultiplierService multiplierService;
    private final UUID playerUuid;
    private final ItemCategory category;

    public MultiCategoryMenu(MultiplierService multiplierService, UUID playerUuid, ItemCategory category) {
        super(54, Component.text(category.getDisplayName() + " Progress", GRAY)
                .decoration(TextDecoration.BOLD, true));
        this.multiplierService = multiplierService;
        this.playerUuid = playerUuid;
        this.category = category;
    }

    @Override
    protected void populate() {
        ItemStack bg = makeBg();
        for (int i = 0; i < 54; i++)
            inventory.setItem(i, bg);

        int level = multiplierService.getLevel(playerUuid, category);
        long xpIn = multiplierService.getXpInLevel(playerUuid, category);
        long xpTo = multiplierService.getXpToNextLevel(playerUuid, category);

        for (int i = 0; i < PATH_SLOTS.length; i++) {
            int slot = PATH_SLOTS[i];
            int stage = i + 1;
            double stageMult = 1.0 + stage * 0.1;

            Material mat;
            TextColor nameColor;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());

            if (i < level) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                nameColor = GREEN;
                lore.add(Component.text("  Multiplier: ", GRAY)
                        .append(Component.text(String.format("%.1fx", stageMult), GREEN))
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("  ✔ Complete", GREEN)
                        .decoration(TextDecoration.ITALIC, false));
            } else if (i == level && level < MultiplierService.MAX_LEVEL) {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
                nameColor = YELLOW;
                long total = xpIn + xpTo;
                double pct = total == 0 ? 0.0 : (xpIn * 100.0 / total);
                lore.add(Component.text("  Multiplier: ", GRAY)
                        .append(Component.text(String.format("%.1fx", stageMult), YELLOW))
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text(String.format("  $%s / $%s  (%.1f%%)",
                        formatShort(xpIn), formatShort(total), pct), WHITE)
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                mat = Material.WHITE_STAINED_GLASS_PANE;
                nameColor = GRAY;
                lore.add(Component.text("  Multiplier: ", GRAY)
                        .append(Component.text(String.format("%.1fx", stageMult), GRAY))
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("  🔒 Locked", GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());

            ItemStack pane = new ItemStack(mat);
            ItemMeta meta = pane.getItemMeta();
            meta.displayName(Component.text("Stage " + stage, nameColor)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            pane.setItemMeta(meta);
            inventory.setItem(slot, pane);
        }

        // Slot 0: category icon with current multiplier summary
        Material iconMat = CAT_ICONS[category.ordinal()];
        if (iconMat == null)
            iconMat = Material.CHEST;
        ItemStack icon = new ItemStack(iconMat);
        ItemMeta iconMeta = icon.getItemMeta();
        iconMeta.displayName(Component.text(category.getDisplayName(), GOLD)
                .decoration(TextDecoration.ITALIC, false));
        double curMult = multiplierService.getMultiplier(playerUuid, category);
        iconMeta.lore(List.of(
                Component.empty(),
                Component.text("  Current Multiplier: ", GRAY)
                        .append(Component.text(String.format("%.1fx", curMult),
                                level >= MultiplierService.MAX_LEVEL ? GREEN : YELLOW))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("  Level: " + level + " / " + MultiplierService.MAX_LEVEL, GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty()));
        icon.setItemMeta(iconMeta);
        inventory.setItem(1, icon);

        // Slot 45: red back button
        ItemStack back = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(Component.text("Back", RED).decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(backMeta);
        inventory.setItem(45, back);
        onClick(45, event -> {
            Player p = (Player) event.getWhoClicked();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> new MultiMainMenu(multiplierService, playerUuid).open(p));
        });
    }

    private ItemStack makeBg() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        pane.setItemMeta(meta);
        return pane;
    }

    private static String formatShort(long val) {
        if (val >= 1_000_000)
            return String.format("%.1fM", val / 1_000_000.0);
        if (val >= 1_000)
            return String.format("%.0fK", val / 1_000.0);
        return String.valueOf(val);
    }
}
