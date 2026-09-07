package dev.smpeconomy.gui;

import org.bukkit.Material;

public record ShopEntry(Material material, String displayName, int quantity, double unitPrice) {

    public double totalPrice() {
        return unitPrice * quantity;
    }

    /** Creates a single-unit entry. */
    public static ShopEntry of(Material mat, String name, double price) {
        return new ShopEntry(mat, name, 1, price);
    }

    /** Creates a multi-unit entry (e.g. End Stone ×16). */
    public static ShopEntry of(Material mat, String name, int qty, double priceEach) {
        return new ShopEntry(mat, name, qty, priceEach);
    }
}
