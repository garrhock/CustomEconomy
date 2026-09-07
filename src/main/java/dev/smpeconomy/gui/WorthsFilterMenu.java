package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.gui.WorthsMenu.WorthSort;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.function.Consumer;

/**
 * 3-row sort picker for the {@link WorthsMenu} catalog, mirroring
 * {@link PlayerShopFilterMenu}. The caller's {@code onSelect} applies the choice
 * and reopens the catalog; the active option glints.
 */
public final class WorthsFilterMenu extends BaseGui {

    private record SortOption(WorthSort sort, Material icon, String label, String description) {}

    private static final SortOption[] OPTIONS = {
        new SortOption(WorthSort.PRICE_HIGH, Material.GOLD_INGOT, "Price: High → Low",  "Most valuable first"),
        new SortOption(WorthSort.PRICE_LOW,  Material.IRON_INGOT, "Price: Low → High",  "Least valuable first"),
        new SortOption(WorthSort.ALPHA_AZ,   Material.OAK_SIGN,   "A → Z",              "Alphabetical by name"),
        new SortOption(WorthSort.ALPHA_ZA,   Material.DARK_OAK_SIGN, "Z → A",           "Reverse alphabetical"),
        new SortOption(WorthSort.CATEGORY,   Material.CHEST,      "By Category",        "Grouped by category"),
    };

    // Five options centred in the middle row.
    private static final int[] OPTION_SLOTS = { 11, 12, 13, 14, 15 };

    private final WorthSort current;
    private final Consumer<WorthSort> onSelect;
    private final Runnable onBack;

    public WorthsFilterMenu(WorthSort current, Consumer<WorthSort> onSelect, Runnable onBack) {
        super(27, Component.text("Sort", GuiUtil.GRAY).decoration(TextDecoration.BOLD, true));
        this.current  = current;
        this.onSelect = onSelect;
        this.onBack   = onBack;
    }

    @Override
    protected void populate() {
        clearHandlers();
        inventory.clear();

        ItemStack glass = GuiUtil.glass();
        for (int i = 0; i < 27; i++) inventory.setItem(i, glass);

        for (int i = 0; i < OPTIONS.length; i++) {
            SortOption opt = OPTIONS[i];
            int slot       = OPTION_SLOTS[i];
            inventory.setItem(slot, makeOptionIcon(opt, opt.sort() == current));
            onClick(slot, e -> onSelect.accept(opt.sort()));
        }

        inventory.setItem(22, GuiUtil.nav(Material.RED_STAINED_GLASS_PANE, "Back", GuiUtil.RED));
        onClick(22, e -> {
            Player p = (Player) e.getWhoClicked();
            p.closeInventory();
            CustomEconomy plugin = CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });
    }

    private ItemStack makeOptionIcon(SortOption opt, boolean active) {
        ItemStack icon = new ItemStack(opt.icon());
        ItemMeta meta  = icon.getItemMeta();
        TextColor nameColor = active ? GuiUtil.GREEN : GuiUtil.GOLD;
        meta.displayName(Component.text(opt.label(), nameColor).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.empty(),
            GuiUtil.line("  " + opt.description()),
            Component.empty(),
            active ? GuiUtil.line("  ✔ Currently active", GuiUtil.GREEN)
                   : GuiUtil.line("  Click to apply", GuiUtil.GRAY)
        ));
        if (active) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        icon.setItemMeta(meta);
        return icon;
    }
}
