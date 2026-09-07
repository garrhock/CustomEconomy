package dev.smpeconomy.model;

/**
 * Immutable value object describing what an item is worth and how its
 * price behaves under dynamic market pressure (Phase 2).
 */
public final class ItemWorth {

    private final String key;
    private final String displayName;
    private final ItemCategory category;
    private final double basePrice;
    private final double minPrice;
    private final double maxPrice;
    private final double volatility;
    private final double recoveryRate;
    private final double xpPerUnit;
    private final double buyPrice;

    private ItemWorth(Builder b) {
        this.key          = b.key;
        this.displayName  = b.displayName != null ? b.displayName : b.key;
        this.category     = b.category;
        this.basePrice    = b.basePrice;
        this.minPrice     = b.minPrice >= 0 ? b.minPrice : b.basePrice * 0.10;
        this.maxPrice     = b.maxPrice >= 0 ? b.maxPrice : b.basePrice * 5.00;
        this.volatility   = b.volatility;
        this.recoveryRate = b.recoveryRate;
        this.xpPerUnit    = b.xpPerUnit;
        this.buyPrice     = b.buyPrice;
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String getKey()           { return key; }
    public String getDisplayName()   { return displayName; }
    public ItemCategory getCategory(){ return category; }
    public double getBasePrice()     { return basePrice; }
    public double getMinPrice()      { return minPrice; }
    public double getMaxPrice()      { return maxPrice; }
    public double getVolatility()    { return volatility; }
    public double getRecoveryRate()  { return recoveryRate; }
    public double getXpPerUnit()     { return xpPerUnit; }
    public double getBuyPrice()      { return buyPrice; }
    public boolean isBuyable()       { return buyPrice > 0; }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder(String key) {
        return new Builder(key);
    }

    public static final class Builder {
        private final String key;
        private String displayName;
        private ItemCategory category = ItemCategory.NATURAL_ITEMS;
        private double basePrice;
        private double minPrice  = -1;
        private double maxPrice  = -1;
        private double volatility   = 0.30;
        private double recoveryRate = 0.05;
        private double xpPerUnit    = 1.0;
        private double buyPrice     = -1;

        private Builder(String key) { this.key = key; }

        public Builder displayName(String v)    { this.displayName  = v; return this; }
        public Builder category(ItemCategory v) { this.category     = v; return this; }
        public Builder basePrice(double v)      { this.basePrice    = v; return this; }
        public Builder minPrice(double v)       { this.minPrice     = v; return this; }
        public Builder maxPrice(double v)       { this.maxPrice     = v; return this; }
        public Builder volatility(double v)     { this.volatility   = v; return this; }
        public Builder recoveryRate(double v)   { this.recoveryRate = v; return this; }
        public Builder xpPerUnit(double v)      { this.xpPerUnit    = v; return this; }
        public Builder buyPrice(double v)       { this.buyPrice     = v; return this; }

        public ItemWorth build() {
            if (basePrice <= 0) throw new IllegalStateException("basePrice must be positive for key: " + key);
            return new ItemWorth(this);
        }
    }
}
