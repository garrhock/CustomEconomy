package dev.smpeconomy.api;

import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.model.SellResult;
import dev.smpeconomy.model.Transaction;
import dev.smpeconomy.service.SellService;
import dev.smpeconomy.service.WorthService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Public API for other plugins to hook into CustomEconomy.
 *
 * Obtain via: CustomEconomy.getInstance().getAPI()
 *
 * This facade is stable across internal refactors. Methods marked
 * "Phase 2" will return defaults until that phase is deployed.
 */
public final class CustomEconomyAPI {

    private final WorthService worthService;
    private final SellService sellService;

    public CustomEconomyAPI(WorthService worthService, SellService sellService) {
        this.worthService = worthService;
        this.sellService  = sellService;
    }

    // ── Worth queries ────────────────────────────────────────────────────────

    /**
     * Returns the configured {@link ItemWorth} for an item, or empty if not sellable.
     */
    public Optional<ItemWorth> getItemWorth(ItemStack item) {
        return worthService.getItemWorth(item);
    }

    /**
     * Returns the effective sell price per unit for a player (includes
     * market factors and multipliers once Phase 2 is deployed).
     * Returns -1 if the item has no configured price.
     */
    public double getSellPrice(ItemStack item, Player player) {
        return worthService.getSellPrice(item, player);
    }

    /**
     * Returns the player's current category multiplier.
     * Returns 1.0 until Phase 2 MultiplierService is deployed.
     */
    public double getMultiplier(Player player, dev.smpeconomy.model.ItemCategory category) {
        return worthService.getMultiplier(player, category);
    }

    // ── Sell operations ──────────────────────────────────────────────────────

    /**
     * Programmatically sells an array of items for a player.
     * Fires {@link dev.smpeconomy.api.event.PlayerSellEvent} — can be cancelled.
     * Must be called from the main thread.
     */
    public SellResult sellItems(Player player, ItemStack[] items) {
        return sellService.sellItems(player, items, Transaction.Source.ADMIN);
    }

    /**
     * Sells items that aren't in the player's inventory - a spawner buffer, a staging GUI.
     * Nothing is taken from the inventory; the caller clears its own source.
     * Unpriced items are not sold and come back in {@link SellResult#getReturned()}.
     */
    public SellResult sellExternal(Player player, ItemStack[] items, Transaction.Source source) {
        return sellService.sellExternal(player, items, source);
    }

    /**
     * Returns the total number of items with configured prices.
     */
    public int getLoadedItemCount() {
        return worthService.getItemCount();
    }
}
