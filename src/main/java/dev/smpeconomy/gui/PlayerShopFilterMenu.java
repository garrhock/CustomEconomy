package dev.smpeconomy.gui;

import dev.smpeconomy.database.repository.PlayerShopRepository.SortOrder;
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
 * 3-row sort/filter selection menu.
 * The caller provides a callback that receives the chosen SortOrder and a
 * Runnable that reopens the parent menu.
 */
public final class PlayerShopFilterMenu extends BaseGui {

    private static final TextColor GOLD  = GuiUtil.GOLD;
    private static final TextColor GRAY  = GuiUtil.GRAY;
    private static final TextColor GREEN = GuiUtil.GREEN;
    private static final TextColor RED   = GuiUtil.RED;

    private static final record SortOption(SortOrder order, Material icon,
                                            String label, String description) {}

    private static final SortOption[] OPTIONS = {
        new SortOption(SortOrder.PRICE_HIGH,   Material.GOLD_INGOT,    "Price: High → Low",   "Most expensive first"),
        new SortOption(SortOrder.PRICE_LOW,    Material.IRON_INGOT,    "Price: Low → High",   "Cheapest first"),
        new SortOption(SortOrder.QTY_HIGH,     Material.CHEST,         "Quantity: High → Low","Most available first"),
        new SortOption(SortOrder.QTY_LOW,      Material.ITEM_FRAME,    "Quantity: Low → High","Least available first"),
        new SortOption(SortOrder.NEWEST,       Material.CLOCK,         "Newest First",        "Most recently listed"),
        new SortOption(SortOrder.OLDEST,       Material.COBWEB,        "Oldest First",        "Listed longest ago"),
        new SortOption(SortOrder.ALPHABETICAL, Material.OAK_SIGN,      "A → Z",               "Alphabetical by name"),
    };

    // Slots for options across two centre rows, centred
    private static final int[] OPTION_SLOTS = { 10, 12, 14, 16, 11, 13, 15 };

    private final SortOrder current;
    private final Consumer<SortOrder> onSelect;
    private final Runnable onBack;

    public PlayerShopFilterMenu(SortOrder current, Consumer<SortOrder> onSelect, Runnable onBack) {
        super(27, Component.text("Sort & Filter", GRAY).decoration(TextDecoration.BOLD, true));
        this.current  = current;
        this.onSelect = onSelect;
        this.onBack   = onBack;
    }

    @Override
    protected void populate() {
        inventory.clear();

        ItemStack glass = makeGlass();
        for (int i = 0; i < 27; i++) inventory.setItem(i, glass);

        for (int i = 0; i < OPTIONS.length; i++) {
            SortOption opt  = OPTIONS[i];
            int slot        = OPTION_SLOTS[i];
            boolean active  = opt.order() == current;
            inventory.setItem(slot, makeOptionIcon(opt, active));
            onClick(slot, e -> {
                onSelect.accept(opt.order());
                // onSelect is responsible for reopening the parent menu
            });
        }

        inventory.setItem(22, makeBack());
        onClick(22, e -> {
            Player p = (Player) e.getWhoClicked();
            p.closeInventory();
            dev.smpeconomy.CustomEconomy plugin = dev.smpeconomy.CustomEconomy.getInstance();
            plugin.getServer().getScheduler().runTask(plugin, onBack);
        });
    }

    private ItemStack makeOptionIcon(SortOption opt, boolean active) {
        ItemStack icon = new ItemStack(opt.icon());
        ItemMeta meta  = icon.getItemMeta();
        TextColor nameColor = active ? GREEN : GOLD;
        meta.displayName(Component.text(opt.label(), nameColor).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.empty(),
            Component.text("  " + opt.description(), GRAY).decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            active
                ? Component.text("  ✔ Currently active", GREEN).decoration(TextDecoration.ITALIC, false)
                : Component.text("  Click to apply", GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        if (active) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack makeBack() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta  = item.getItemMeta();
        meta.displayName(Component.text("Back", RED).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeGlass() {
        return GuiUtil.glass();
    }
}
