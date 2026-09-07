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

import java.util.List;
import java.util.UUID;

public final class MultiMainMenu extends BaseGui {

    private static final TextColor GOLD = TextColor.color(0xFFAA00);
    private static final TextColor GREEN = TextColor.color(0x55FF55);
    private static final TextColor GRAY = NamedTextColor.GRAY;

    private static final Material BORDER = Material.GRAY_STAINED_GLASS_PANE;

    record CategoryEntry(ItemCategory category, Material icon) {
    }

    // 9 categories arranged in a 3×3 grid starting at slot 10
    private static final CategoryEntry[] CATEGORIES = {
            new CategoryEntry(ItemCategory.CROPS, Material.WHEAT),
            new CategoryEntry(ItemCategory.ORES, Material.DIAMOND_ORE),
            new CategoryEntry(ItemCategory.MOB_DROPS, Material.ROTTEN_FLESH),
            new CategoryEntry(ItemCategory.NATURAL_ITEMS, Material.OAK_LOG),
            new CategoryEntry(ItemCategory.ARMOR_AND_TOOLS, Material.IRON_SWORD),
            new CategoryEntry(ItemCategory.FISH, Material.COD),
            new CategoryEntry(ItemCategory.ENCHANTED_BOOKS, Material.ENCHANTED_BOOK),
            new CategoryEntry(ItemCategory.POTIONS, Material.POTION),
            new CategoryEntry(ItemCategory.BLOCKS, Material.STONE),
    };

    private final MultiplierService multiplierService;
    private final UUID playerUuid;

    public MultiMainMenu(MultiplierService multiplierService, UUID playerUuid) {
        // 1-row chest (9 slots) — one category icon per slot
        super(9, Component.text("Sell Multiplier", GRAY).decoration(TextDecoration.BOLD, true));
        this.multiplierService = multiplierService;
        this.playerUuid = playerUuid;
    }

    @Override
    protected void populate() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            CategoryEntry entry = CATEGORIES[i];
            inventory.setItem(i, makeCategoryIcon(entry));
            final ItemCategory cat = entry.category();
            onClick(i, event -> {
                Player p = (Player) event.getWhoClicked();
                CustomEconomy plugin = CustomEconomy.getInstance();
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> new MultiCategoryMenu(multiplierService, playerUuid, cat).open(p));
            });
        }
    }

    private ItemStack makeCategoryIcon(CategoryEntry entry) {
        ItemCategory cat = entry.category();
        int level = multiplierService.getLevel(playerUuid, cat);
        double multiplier = multiplierService.getMultiplier(playerUuid, cat);
        long xp = multiplierService.getXp(playerUuid, cat);
        long xpNext = multiplierService.getXpToNextLevel(playerUuid, cat);

        boolean maxed = level >= MultiplierService.MAX_LEVEL;
        String progressLine = maxed
                ? "MAX LEVEL"
                : xp + " / " + (xp + xpNext) + " XP";

        ItemStack icon = new ItemStack(entry.icon());
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(Component.text(cat.getDisplayName(), GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.empty(),
                Component.text("  Level: ", GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(level, GREEN).decoration(TextDecoration.ITALIC, false)),
                Component.text("  Multiplier: ", GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(String.format("%.1fx", multiplier), GREEN)
                                .decoration(TextDecoration.ITALIC, false)),
                Component.text("  Progress: ", GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(progressLine, GREEN).decoration(TextDecoration.ITALIC, false)),
                Component.empty(),
                Component.text("  ▶ Click for details", GRAY).decoration(TextDecoration.ITALIC, false)));
        icon.setItemMeta(meta);
        return icon;
    }
}
