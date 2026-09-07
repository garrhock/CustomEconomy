package dev.smpeconomy.model;

import java.time.Instant;

/**
 * Immutable snapshot of a single row from the market_prices table.
 * factor() = current_price / base_price; a value of 1.0 means neutral (no market pressure).
 */
public record MarketPrice(
    String  itemKey,
    double  basePrice,
    double  currentPrice,
    long    totalSold,
    boolean priceFrozen,
    Instant lastUpdated
) {
    /** Ratio of current to base price. Returns 1.0 if base is zero (safety). */
    public double factor() {
        return basePrice > 0 ? currentPrice / basePrice : 1.0;
    }
}
