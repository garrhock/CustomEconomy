package dev.smpeconomy.service;

import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.repository.MarketRepository;
import dev.smpeconomy.gui.ShopSection;
import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.model.MarketPrice;
import org.bukkit.Material;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns the live market factor cache and the periodic price-update algorithm.
 *
 * A "factor" is a multiplier on an item's base price.  1.0 = neutral.
 * Values < 1.0 mean the market is flooded (players are selling heavily).
 * Values > 1.0 mean the market is tight (players are buying heavily).
 *
 * The same factor is applied to BOTH the sell price (via WorthService) and the
 * shop buy price (via ShopCategoryMenu), which guarantees the buy-vs-sell exploit
 * invariant is preserved regardless of market movement — the factor cancels in
 * the ratio.
 *
 * Thread safety: {@code factors} is a ConcurrentHashMap replaced via bulk put
 * from a single DB thread, and read lock-free from the main server thread.
 */
public final class MarketService {

    private final MarketRepository repo;
    private final ConfigManager config;
    private final Logger log;

    private final ConcurrentHashMap<String, Double> factors = new ConcurrentHashMap<>();

    /**
     * Circuit breaker state: each item's factor as of the first tick of the
     * current UTC day. In-memory only — after a restart the anchor re-seeds
     * from the current factor, which resets the day's drop allowance (accepted
     * trade-off; the breaker is a safety rail, not bookkeeping).
     */
    private final Map<String, Double> dayAnchors = new HashMap<>();
    private long anchorEpochDay = -1;

    public MarketService(MarketRepository repo, ConfigManager config, Logger log) {
        this.repo   = repo;
        this.config = config;
        this.log    = log;
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    /**
     * Seeds market_prices for every item that should participate in dynamic pricing,
     * then loads the current factors into the in-memory cache.
     * Must be called once after the DB is initialized and ConfigManager is loaded.
     *
     * @param itemWorthMap the parsed items.yml price table
     */
    public void initialize(Map<String, ItemWorth> itemWorthMap) {
        // Seed all items.yml sell-side items
        for (Map.Entry<String, ItemWorth> e : itemWorthMap.entrySet()) {
            repo.seed(e.getKey(), e.getValue().getBasePrice());
        }

        // Seed shop-only items (not in items.yml) using their configured base shop price
        for (ShopSection section : config.getShopSections()) {
            for (var entry : section.getEntries()) {
                String key = entry.material().name().toUpperCase(Locale.ROOT);
                if (!itemWorthMap.containsKey(key)) {
                    repo.seed(key, entry.unitPrice());
                }
            }
        }

        // Load existing factors from DB into the in-memory cache
        refreshCache();

        log.info("[Market] Initialized with " + factors.size() + " tracked items. Enabled=" + config.isMarketEnabled());
    }

    /** Reloads factors from DB into memory without re-seeding. */
    public void refreshCache() {
        Map<String, MarketPrice> rows = repo.findAll();
        for (Map.Entry<String, MarketPrice> e : rows.entrySet()) {
            factors.put(e.getKey(), e.getValue().factor());
        }
    }

    // ── Public price API ──────────────────────────────────────────────────────

    /**
     * Returns the market factor for {@code itemKey}.
     * Returns 1.0 if market is disabled or the item is not tracked.
     * Thread-safe: reads from ConcurrentHashMap.
     */
    public double getFactor(String itemKey) {
        if (!config.isMarketEnabled()) return 1.0;
        return factors.getOrDefault(itemKey.toUpperCase(Locale.ROOT), 1.0);
    }

    /**
     * Returns the dynamic shop buy price for a given material and its base shop price.
     * The returned price is what the player actually pays when buying from the shop.
     */
    public double getDynamicShopPrice(Material material, double baseShopPrice) {
        if (!config.isMarketEnabled()) return baseShopPrice;
        double factor = factors.getOrDefault(material.name().toUpperCase(Locale.ROOT), 1.0);
        return baseShopPrice * factor;
    }

    /**
     * Returns the current factor rounded for display: + means price rose, - means fell.
     * Example: 1.25 → "+25%",  0.80 → "-20%",  1.0 → "±0%"
     */
    public String getFactorDisplay(String itemKey) {
        double f = getFactor(itemKey);
        int pct = (int) Math.round((f - 1.0) * 100);
        if (pct > 0)  return "+" + pct + "%";
        if (pct < 0)  return pct + "%";
        return "±0%";
    }

    /** Returns the raw MarketPrice snapshot for an item key, or null if not tracked. */
    public MarketPrice getSnapshot(String itemKey) {
        Map<String, MarketPrice> all = repo.findAll();
        return all.get(itemKey.toUpperCase(Locale.ROOT));
    }

    // ── Admin controls ────────────────────────────────────────────────────────

    public void freezePrice(String itemKey) {
        repo.setFrozen(itemKey, true);
        log.info("[Market] Price frozen: " + itemKey);
    }

    public void unfreezePrice(String itemKey) {
        repo.setFrozen(itemKey, false);
        log.info("[Market] Price unfrozen: " + itemKey);
    }

    public void resetToBase(String itemKey) {
        repo.resetToBase(itemKey);
        factors.put(itemKey.toUpperCase(Locale.ROOT), 1.0);
        log.info("[Market] Price reset to base: " + itemKey);
    }

    public void resetAll() {
        repo.resetAll();
        // Reset all in-memory factors to 1.0 (frozen items keep their factor from DB)
        Map<String, MarketPrice> rows = repo.findAll();
        for (Map.Entry<String, MarketPrice> e : rows.entrySet()) {
            factors.put(e.getKey(), e.getValue().factor());
        }
        log.info("[Market] All non-frozen prices reset to base.");
    }

    // ── Periodic update ───────────────────────────────────────────────────────

    /**
     * Entry point for the server scheduler.  Dispatches the update to the
     * dedicated DB thread so the main thread is never blocked.
     */
    public void runUpdate() {
        if (!config.isMarketEnabled()) return;
        CompletableFuture.runAsync(this::doUpdate, MarketRepository.DB_POOL);
    }

    private void doUpdate() {
        Instant windowStart = Instant.now().minus(config.getMarketRollingWindowHours(), ChronoUnit.HOURS);

        Map<String, Long> sellVols = repo.getSellVolume(windowStart);
        Map<String, Long> buyVols  = repo.getBuyVolume(windowStart);
        Map<String, MarketPrice> prices = repo.findAll();
        Map<String, ItemWorth> worthMap = config.getItemWorthMap();

        // Adaptive market depth: the pressure baseline scales with how many
        // distinct players actually sold in the window, floored at a minimum
        // participant count ("NPC merchant crowd"). A 3-player night behaves
        // like a 10-participant market; at scale, real volume dominates. This
        // is a market-wide parameter — never a per-player adjustment.
        double baseline;
        if (config.isMarketAdaptiveDepthEnabled()) {
            long sellers = repo.getUniqueSellers(windowStart);
            baseline = Math.max(config.getMarketMinParticipants(), sellers)
                    * config.getMarketBaselinePerSeller();
        } else {
            baseline = config.getMarketBaselineVolume();
        }
        double defVolatility  = config.getMarketDefaultVolatility();
        double defRecovery    = config.getMarketDefaultRecovery();

        // Circuit breaker: roll the day anchors at the UTC-day boundary
        // (runs only on the single DB thread, so plain HashMap is safe).
        boolean breakerOn = config.isMarketCircuitBreakerEnabled();
        long today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay();
        if (today != anchorEpochDay) {
            dayAnchors.clear();
            anchorEpochDay = today;
        }
        double maxDailyDrop = config.getMarketMaxDailyDrop();

        Map<String, Double> newPrices  = new HashMap<>();
        Map<String, Double> newFactors = new HashMap<>();

        for (Map.Entry<String, MarketPrice> e : prices.entrySet()) {
            String key     = e.getKey();
            MarketPrice mp = e.getValue();

            if (mp.priceFrozen()) {
                newFactors.put(key, mp.factor());
                continue;
            }

            // Item-specific parameters from items.yml, fall back to config defaults
            ItemWorth worth = worthMap.get(key);
            double volatility    = worth != null ? worth.getVolatility()    : defVolatility;
            double recoveryRate  = worth != null ? worth.getRecoveryRate()  : defRecovery;
            double minPrice      = worth != null ? worth.getMinPrice()      : mp.basePrice() * 0.10;
            double maxPrice      = worth != null ? worth.getMaxPrice()      : mp.basePrice() * 5.00;

            // ── 1. Start from current factor ──────────────────────────────────
            double factor = mp.factor();

            // ── 2. Recovery toward neutral (1.0) ─────────────────────────────
            // Each tick nudges the factor toward 1.0 proportionally.
            factor = factor + (1.0 - factor) * recoveryRate;

            // ── 3. Sell pressure (high sell volume → price falls) ─────────────
            long sellVol = sellVols.getOrDefault(key, 0L);
            double sellPressure = Math.min(sellVol / baseline, 1.0) * volatility;
            factor *= (1.0 - sellPressure);

            // ── 4. Buy pressure (high buy volume → price rises, weaker) ───────
            long buyVol = buyVols.getOrDefault(key, 0L);
            double buyPressure = Math.min(buyVol / baseline, 1.0) * volatility * 0.5;
            factor *= (1.0 + buyPressure);

            // ── 5. Clamp to configured price bounds ───────────────────────────
            double newPrice = mp.basePrice() * factor;
            newPrice = Math.max(minPrice, Math.min(maxPrice, newPrice));
            factor   = mp.basePrice() > 0 ? newPrice / mp.basePrice() : 1.0;

            // ── 6. Circuit breaker: within one UTC day, a price may not fall
            //       more than max-daily-drop below its day-start value. The
            //       anchor was itself clamped when it was live, so raising the
            //       factor to the breaker floor can never exceed maxPrice.
            if (breakerOn) {
                double anchor = dayAnchors.computeIfAbsent(key, k -> mp.factor());
                double breakerFloor = anchor * (1.0 - maxDailyDrop);
                if (factor < breakerFloor) {
                    factor   = breakerFloor;
                    newPrice = mp.basePrice() * factor;
                }
            }

            newPrices.put(key, newPrice);
            newFactors.put(key, factor);
        }

        // Batch-write to DB (skips frozen rows at SQL level)
        repo.updatePrices(newPrices);

        // Atomically refresh in-memory cache
        factors.putAll(newFactors);

        log.fine("[Market] Price tick complete — updated " + newPrices.size() + " item(s).");
    }
}
