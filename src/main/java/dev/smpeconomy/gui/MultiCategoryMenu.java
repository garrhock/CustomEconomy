package dev.smpeconomy.gui;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;
import dev.smpeconomy.message.MessageKey;

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
        super(54, CoreKeys.MULTI_CATEGORY_TITLE, TokenBag.of().put("category", category.getDisplayName()));
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
            MessageKey stageKey;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());

            if (i < level) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                stageKey = CoreKeys.MULTI_CATEGORY_STAGE_COMPLETE;
                lore.add(messages.lore(CoreKeys.MULTI_CATEGORY_MULT_COMPLETE, TokenBag.of().put("multiplier", String.format("%.1fx", stageMult))));
                lore.add(messages.lore(CoreKeys.MULTI_CATEGORY_COMPLETE));
            } else if (i == level && level < MultiplierService.MAX_LEVEL) {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
                stageKey = CoreKeys.MULTI_CATEGORY_STAGE_CURRENT;
                long total = xpIn + xpTo;
                double pct = total == 0 ? 0.0 : (xpIn * 100.0 / total);
                lore.add(messages.lore(CoreKeys.MULTI_CATEGORY_MULT_CURRENT, TokenBag.of().put("multiplier", String.format("%.1fx", stageMult))));
                lore.add(messages.lore(CoreKeys.MULTI_CATEGORY_PROGRESS, TokenBag.of().put("current", formatShort(xpIn)).put("total", formatShort(total)).put("percent", String.format("%.1f", pct))));
            } else {
                mat = Material.WHITE_STAINED_GLASS_PANE;
                stageKey = CoreKeys.MULTI_CATEGORY_STAGE_LOCKED;
                lore.add(messages.lore(CoreKeys.MULTI_CATEGORY_MULT_LOCKED, TokenBag.of().put("multiplier", String.format("%.1fx", stageMult))));
                lore.add(messages.lore(CoreKeys.MULTI_CATEGORY_LOCKED));
            }
            lore.add(Component.empty());

            ItemStack pane = new ItemStack(mat);
            ItemMeta meta = pane.getItemMeta();
            meta.displayName(messages.lore(stageKey, TokenBag.of().put("stage", stage)));
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
        iconMeta.displayName(messages.lore(CoreKeys.MULTI_CATEGORY_ICON_NAME, TokenBag.of().put("category", category.getDisplayName())));
        double curMult = multiplierService.getMultiplier(playerUuid, category);
        iconMeta.lore(List.of(
                Component.empty(),
                messages.lore(level >= MultiplierService.MAX_LEVEL
                                ? CoreKeys.MULTI_CATEGORY_CURRENT_MULT_MAX
                                : CoreKeys.MULTI_CATEGORY_CURRENT_MULT, TokenBag.of().put("multiplier", String.format("%.1fx", curMult))),
                messages.lore(CoreKeys.MULTI_CATEGORY_LEVEL, TokenBag.of().put("level", level).put("max", MultiplierService.MAX_LEVEL)),
                Component.empty()));
        icon.setItemMeta(iconMeta);
        inventory.setItem(1, icon);

        // Slot 45: red back button
        ItemStack back = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(messages.lore(CoreKeys.MULTI_CATEGORY_BACK));
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
