package dev.smpeconomy.gui;

import org.bukkit.Material;

import java.util.List;

/**
 * A single admin-shop section loaded from shop.yml at runtime.
 * No longer an enum — instances are created by ConfigManager.parseShop()
 * and can be refreshed on /ecoadmin reload without restarting the server.
 */
public final class ShopSection {

    private final String displayName;
    private final Material icon;
    private final List<ShopEntry> entries;

    public ShopSection(String displayName, Material icon, List<ShopEntry> entries) {
        this.displayName = displayName;
        this.icon        = icon;
        this.entries     = List.copyOf(entries);
    }

    public String          getDisplayName() { return displayName; }
    public Material        getIcon()        { return icon; }
    public List<ShopEntry> getEntries()     { return entries; }

    /** Human-readable label for log messages (replaces former enum name()). */
    public String label() { return displayName; }
}
