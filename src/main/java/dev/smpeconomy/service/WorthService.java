package dev.smpeconomy.service;

import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.util.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Core price-lookup service.
 *
 * Thread-safe: ConfigManager#getItemWorthMap() is replaced atomically on reload;
 * callers always get a consistent snapshot.
 */
public final class WorthService {

    private final ConfigManager config;
    private MultiplierService multiplierService;
    private MarketService marketService;

    public WorthService(ConfigManager config) {
        this.config = config;
    }

    /** Injected after construction to avoid circular dependencies. */
    public void setMultiplierService(MultiplierService ms) {
        this.multiplierService = ms;
    }

    /** Injected after construction to avoid circular dependencies. */
    public void setMarketService(MarketService ms) {
        this.marketService = ms;
    }

    // ── Item worth lookup ────────────────────────────────────────────────────

    /**
     * Returns the {@link ItemWorth} descriptor for an item, or empty if the
     * item has no configured price.
     */
    public Optional<ItemWorth> getItemWorth(ItemStack item) {
        String key = ItemUtil.getKey(item);
        if (key == null) return Optional.empty();
        return Optional.ofNullable(config.getItemWorthMap().get(key.toUpperCase(Locale.ROOT)));
    }

    /**
     * Returns the effective sell price per unit for {@code item} for {@code player}.
     *
     * Phase 1: base price only.
     * Phase 2: base price × dynamic market factor × player category multiplier.
     *
     * Returns -1 if the item is not sellable.
     */
    public double getSellPrice(ItemStack item, Player player) {
        Optional<ItemWorth> opt = getItemWorth(item);
        if (opt.isEmpty()) return -1;

        ItemWorth worth = opt.get();
        double price = worth.getBasePrice();

        if (multiplierService != null && player != null) {
            price *= multiplierService.getMultiplier(player.getUniqueId(), worth.getCategory());
        }

        if (marketService != null) {
            price *= marketService.getFactor(worth.getKey());
        }

        return price;
    }

    /**
     * Returns the effective sell price per unit by string key (for admin commands).
     */
    public double getSellPriceByKey(String key) {
        ItemWorth worth = config.getItemWorthMap().get(key.toUpperCase(Locale.ROOT));
        return worth != null ? worth.getBasePrice() : -1;
    }

    /**
     * Returns the current effective multiplier for a player in a category.
     * Phase 1 always returns 1.0.
     */
    public double getMultiplier(Player player, dev.smpeconomy.model.ItemCategory category) {
        if (multiplierService == null || player == null || category == null) return 1.0;
        return multiplierService.getMultiplier(player.getUniqueId(), category);
    }

    public int getItemCount() {
        return config.getItemWorthMap().size();
    }

    public Collection<ItemWorth> getAllItems() {
        return config.getItemWorthMap().values();
    }

    /**
     * Looks up worth by key string — useful for /worth <name> tab completion.
     */
    public Map<String, ItemWorth> getItemWorthMap() {
        return config.getItemWorthMap();
    }
}
