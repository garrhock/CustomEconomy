package dev.smpeconomy.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates canonical string keys used to look items up in the price table.
 *
 * Key format (in priority order):
 *   1. ItemsAdder item  → "ia:<namespace>:<id>"
 *   2. Custom model data → "MATERIAL_NAME:CMD"
 *   3. Plain material   → "MATERIAL_NAME"
 *
 * All keys are uppercase-normalized for consistent map lookups.
 */
public final class ItemUtil {

    // ItemsAdder stores its id here
    private static final NamespacedKey IA_KEY = new NamespacedKey("itemsadder", "id");

    private ItemUtil() {}

    /**
     * Returns the canonical price-lookup key for an ItemStack.
     * Returns {@code null} for null/air items.
     */
    public static String getKey(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // ── 1. ItemsAdder ────────────────────────────────────────────────
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc.has(IA_KEY, PersistentDataType.STRING)) {
                String iaId = pdc.get(IA_KEY, PersistentDataType.STRING);
                if (iaId != null && !iaId.isBlank()) {
                    return "ia:" + iaId.toLowerCase(java.util.Locale.ROOT);
                }
            }

            // ── 2. Custom model data ──────────────────────────────────────────
            if (meta.hasCustomModelData()) {
                return item.getType().name() + ":" + meta.getCustomModelData();
            }
        }

        // ── 3. Plain material ─────────────────────────────────────────────────
        return item.getType().name();
    }

    /**
     * Returns a human-readable display name for an ItemStack.
     * Uses the item's display name if set, otherwise prettifies the material name.
     */
    public static String getDisplayName(ItemStack item) {
        if (item == null) return "Unknown";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            // Strip Adventure formatting tags for plain-text use
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(meta.displayName());
        }
        // "IRON_INGOT" → "Iron Ingot"
        String mat = item.getType().name().replace('_', ' ');
        StringBuilder sb = new StringBuilder(mat.length());
        boolean capitalize = true;
        for (char c : mat.toCharArray()) {
            sb.append(capitalize ? Character.toUpperCase(c) : Character.toLowerCase(c));
            capitalize = (c == ' ');
        }
        return sb.toString();
    }

    /** Returns true if an ItemStack is non-null, non-air, and has a positive amount. */
    public static boolean isValid(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    /** Returns true if the material is any colour of shulker box. */
    public static boolean isShulkerBox(Material material) {
        String name = material.name();
        return name.equals("SHULKER_BOX") || name.endsWith("_SHULKER_BOX");
    }

    /** Returns the non-null, non-air contents of a shulker box item, or an empty list. */
    public static List<ItemStack> getShulkerContents(ItemStack shulker) {
        if (shulker.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox box) {
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack inner : box.getInventory().getContents()) {
                if (isValid(inner)) result.add(inner);
            }
            return result;
        }
        return List.of();
    }

    /** Returns a copy of the shulker box with its inventory cleared (amount = 1). */
    public static ItemStack emptyShulkerCopy(ItemStack shulker) {
        ItemStack copy = shulker.clone();
        copy.setAmount(1);
        if (copy.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof ShulkerBox box) {
            box.getInventory().clear();
            bsm.setBlockState(box);
            copy.setItemMeta(bsm);
        }
        return copy;
    }

    /**
     * Creates an ItemStack from a price-table key (plain material only).
     * Returns null if the key maps to an unknown material or is a custom-item key.
     */
    public static ItemStack createFromKey(String key) {
        if (key == null || key.startsWith("ia:") || key.contains(":")) return null;
        Material mat = Material.matchMaterial(key);
        if (mat == null || mat.isAir() || !mat.isItem()) return null;
        return new ItemStack(mat, 1);
    }
}
