package dev.smpeconomy.model;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable result of a sell operation, built line-by-line then frozen.
 */
public final class SellResult {

    /** Per-item-type breakdown line. */
    public record Line(String displayName, int quantity, double priceEach, double subtotal) {}

    private final boolean success;
    private final double totalEarned;
    private final int quantitySold;
    private final double multiplier;
    private final Map<String, Line> breakdown;
    private final List<ItemStack> returned;

    private SellResult(Builder b) {
        this.success      = b.totalEarned > 0;
        this.totalEarned  = b.totalEarned;
        this.quantitySold = b.quantitySold;
        this.multiplier   = b.multiplier;
        this.breakdown    = Collections.unmodifiableMap(b.breakdown);
        this.returned     = Collections.unmodifiableList(b.returned);
    }

    public boolean isSuccess()              { return success; }
    public double getTotalEarned()          { return totalEarned; }
    public int getQuantitySold()            { return quantitySold; }
    public double getMultiplier()           { return multiplier; }
    public Map<String, Line> getBreakdown() { return breakdown; }
    public List<ItemStack> getReturned()    { return returned; }

    public static Builder builder() { return new Builder(); }

    public static SellResult empty() { return builder().build(); }

    public static final class Builder {
        private double totalEarned  = 0;
        private int    quantitySold = 0;
        private double multiplier   = 1.0;
        private final Map<String, Line> breakdown = new LinkedHashMap<>();
        private final List<ItemStack>   returned  = new java.util.ArrayList<>();

        public Builder multiplier(double m) { this.multiplier = m; return this; }

        public Builder addLine(String key, String displayName, int qty, double priceEach) {
            double sub = qty * priceEach * multiplier;
            breakdown.merge(key, new Line(displayName, qty, priceEach, sub),
                (old, next) -> new Line(displayName, old.quantity() + next.quantity(),
                    priceEach, old.subtotal() + next.subtotal()));
            totalEarned  += sub;
            quantitySold += qty;
            return this;
        }

        public Builder addReturned(ItemStack item) {
            if (item != null && !item.getType().isAir()) returned.add(item);
            return this;
        }

        public SellResult build() { return new SellResult(this); }
    }
}
