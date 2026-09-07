package dev.smpeconomy.service;

import dev.smpeconomy.database.repository.TransactionRepository;
import dev.smpeconomy.hook.VaultHook;
import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.model.Transaction;
import dev.smpeconomy.util.ItemUtil;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.Map;

public final class ShopService {

    private final VaultHook vault;
    private final TransactionRepository txRepo;
    private final MarketService marketService;

    public ShopService(VaultHook vault, TransactionRepository txRepo, MarketService marketService) {
        this.vault         = vault;
        this.txRepo        = txRepo;
        this.marketService = marketService;
    }

    public enum BuyResult {
        SUCCESS,
        PARTIAL_SUCCESS,   // purchased OK, some items dropped on the ground
        INSUFFICIENT_FUNDS,
        NO_INVENTORY_SPACE,
        NOT_BUYABLE
    }

    /**
     * Attempts to purchase {@code amount} units of the item described by
     * {@code worth}.
     * Must be called on the main thread.
     */
    public BuyResult buy(Player player, ItemWorth worth, int amount) {
        if (!worth.isBuyable()) {
            playNo(player);
            return BuyResult.NOT_BUYABLE;
        }

        double unitPrice = worth.getBuyPrice() * marketService.getFactor(worth.getKey());
        double cost = unitPrice * amount;
        if (!vault.has(player, cost)) {
            playNo(player);
            return BuyResult.INSUFFICIENT_FUNDS;
        }

        ItemStack give = ItemUtil.createFromKey(worth.getKey());
        if (give == null) {
            playNo(player);
            return BuyResult.NOT_BUYABLE;
        }
        give.setAmount(amount);

        vault.withdraw(player, cost);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(give);
        overflow.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));

        playYes(player);

        txRepo.enqueue(new Transaction(
                0, player.getUniqueId(), worth.getKey(),
                amount, -unitPrice, 1.0, -cost,
                Transaction.Source.SHOP_BUY,
                Instant.now()));

        return overflow.isEmpty() ? BuyResult.SUCCESS : BuyResult.PARTIAL_SUCCESS;
    }

    /**
     * Purchases a fixed-shop item by Material.  {@code dynamicUnitPrice} must be
     * pre-computed by the caller via {@link MarketService#getDynamicShopPrice}.
     * This keeps the price the player saw in the GUI (when they opened it) locked
     * in — it won't shift between opening the menu and clicking confirm.
     * Must be called on the main thread.
     */
    public BuyResult buyDirect(Player player, Material material, int quantity, double dynamicUnitPrice) {
        double cost = dynamicUnitPrice * quantity;
        if (!vault.has(player, cost)) {
            playNo(player);
            return BuyResult.INSUFFICIENT_FUNDS;
        }

        vault.withdraw(player, cost);
        ItemStack give = new ItemStack(material, quantity);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(give);
        overflow.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));

        playYes(player);

        txRepo.enqueue(new Transaction(
                0, player.getUniqueId(), material.name().toUpperCase(java.util.Locale.ROOT),
                quantity, -dynamicUnitPrice, 1.0, -cost,
                Transaction.Source.SHOP_BUY,
                Instant.now()));

        return overflow.isEmpty() ? BuyResult.SUCCESS : BuyResult.PARTIAL_SUCCESS;
    }

    private void playNo(Player player) {
        try { player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f); }
        catch (Throwable ignored) {}
    }

    private void playYes(Player player) {
        try { player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f); }
        catch (Throwable ignored) {}
    }
}
