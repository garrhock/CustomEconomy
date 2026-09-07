package dev.smpeconomy.model;

public enum ItemCategory {
    CROPS("Crops"),
    ORES("Ores"),
    MOB_DROPS("Mob Drops"),
    NATURAL_ITEMS("Natural Items"),
    ARMOR_AND_TOOLS("Armor & Tools"),
    FISH("Fish"),
    ENCHANTED_BOOKS("Enchanted Books"),
    POTIONS("Potions"),
    BLOCKS("Blocks");

    private final String displayName;

    ItemCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static ItemCategory fromString(String raw) {
        if (raw == null) return NATURAL_ITEMS;
        try {
            return valueOf(raw.toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return NATURAL_ITEMS;
        }
    }
}
