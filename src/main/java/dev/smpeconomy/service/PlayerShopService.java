package dev.smpeconomy.service;

import dev.smpeconomy.message.Messages;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.repository.PlayerShopRepository;
import dev.smpeconomy.database.repository.PlayerShopRepository.SortOrder;
import dev.smpeconomy.database.repository.TransactionRepository;
import dev.smpeconomy.gui.ShopEntry;
import dev.smpeconomy.gui.ShopSection;
import dev.smpeconomy.hook.VaultHook;
import dev.smpeconomy.model.PlayerListing;
import dev.smpeconomy.model.PlayerListing.Status;
import dev.smpeconomy.model.PlayerListing.Type;
import dev.smpeconomy.model.StorageItem;
import dev.smpeconomy.model.Transaction;
import dev.smpeconomy.util.FormatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Business logic for the player-to-player shop.
 *
 * All methods are synchronous and must be called on the main server thread
 * (they touch player inventories and Vault).  DB operations are quick
 * single-row reads/writes; batch operations run off-thread in the expiry task.
 */
public final class PlayerShopService {

    public enum Result {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        INSUFFICIENT_ITEMS,
        MAX_LISTINGS_REACHED,
        INVALID_PRICE,
        PRICE_TOO_LOW,         // sell listing below admin-arbitrage floor
        PRICE_TOO_HIGH,        // buy order above admin-arbitrage ceiling
        LISTING_NOT_FOUND,
        LISTING_UNAVAILABLE,   // no longer active or out of stock
        CANT_TRADE_OWN_LISTING,
        NOT_YOUR_LISTING
    }

    private static final long LISTING_DAYS = 7L;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PlayerShopRepository repo;
    private final TransactionRepository txRepo;
    private final VaultHook vault;
    private final WorthService worthService;
    private final ConfigManager config;
    private final int maxListingsPerPlayer;
    private final Logger log;

    public PlayerShopService(JavaPlugin plugin, PlayerShopRepository repo, TransactionRepository txRepo,
                             VaultHook vault, WorthService worthService, ConfigManager config,
                             int maxListingsPerPlayer, Logger log, Messages messages) {
        this.messages = messages;
        this.plugin               = plugin;
        this.repo                 = repo;
        this.txRepo               = txRepo;
        this.vault                = vault;
        this.worthService         = worthService;
        this.config               = config;
        this.maxListingsPerPlayer = maxListingsPerPlayer;
        this.log                  = log;
    }

    // ── Exploit price guards ───────────────────────────────────────────────────

    /**
     * Minimum legal price for a player SELL listing of {@code material}.
     *
     * If the admin shop pays {@code adminSell} for this item, a buyer could buy
     * cheaply from the player and resell to the admin for {@code adminSell × MAX_MULT}
     * (max sell multiplier), minting server money.  Requiring the listing price to
     * sit at or above that payout closes the loop.  Returns 0 when the item has no
     * admin sell price (nothing to arbitrage against).
     */
    public double getSellPriceFloor(Material material) {
        double adminSell = worthService.getSellPriceByKey(material.name());
        if (adminSell <= 0) return 0;
        return adminSell * MultiplierService.MAX_MULT;
    }

    /**
     * Maximum legal price for a player BUY order of {@code material}.
     *
     * If the admin shop sells this item for {@code adminBuy}, anyone could source
     * it cheaply from the admin and fulfill a higher-priced buy order for profit.
     * Capping buy orders at the admin buy price closes that loop.  Returns
     * {@link Double#MAX_VALUE} when the admin shop does not sell the item (no cap).
     */
    public double getBuyPriceCeiling(Material material) {
        double adminBuy = adminBuyPrice(material);
        return adminBuy > 0 ? adminBuy : Double.MAX_VALUE;
    }

    /** Looks up the admin shop buy price for a material, or -1 if not sold. */
    private double adminBuyPrice(Material material) {
        for (ShopSection section : config.getShopSections()) {
            for (ShopEntry entry : section.getEntries()) {
                if (entry.material() == material) return entry.unitPrice();
            }
        }
        return -1;
    }

    // ── Listing limit (permission-aware) ───────────────────────────────────────

    /** Permission prefix for per-rank listing limits, e.g. {@code customeco.shop.listings.20}. */
    private static final String LISTINGS_PERM_PREFIX = "customeco.shop.listings.";
    /** Grants an unlimited number of active listings. */
    private static final String LISTINGS_PERM_UNLIMITED = "customeco.shop.listings.unlimited";

    /**
     * Resolves the maximum active listings allowed for {@code player}.
     *
     * Players may carry one or more {@code customeco.shop.listings.<n>} permissions
     * (typically granted per rank); the highest {@code <n>} wins.  The special node
     * {@code customeco.shop.listings.unlimited} removes the cap entirely.  When the
     * player has no such permission, the config default ({@code player-shop.max-listings})
     * applies.  The config value also acts as a floor, so a permission can only ever
     * raise the limit, never lower it below the baseline everyone gets.
     */
    public int getMaxListings(Player player) {
        if (player.hasPermission(LISTINGS_PERM_UNLIMITED)) return Integer.MAX_VALUE;

        int best = maxListingsPerPlayer;
        for (var perm : player.getEffectivePermissions()) {
            if (!perm.getValue()) continue;
            String node = perm.getPermission();
            if (node.length() <= LISTINGS_PERM_PREFIX.length()
                || !node.regionMatches(true, 0, LISTINGS_PERM_PREFIX, 0, LISTINGS_PERM_PREFIX.length())) {
                continue;
            }
            String suffix = node.substring(LISTINGS_PERM_PREFIX.length());
            try {
                best = Math.max(best, Integer.parseInt(suffix.trim()));
            } catch (NumberFormatException ignored) {
                // non-numeric suffix (e.g. "unlimited", already handled) — skip
            }
        }
        return best;
    }

    // ── Create listings ───────────────────────────────────────────────────────

    /**
     * Creates a sell listing.  Items are removed from the seller's inventory
     * immediately and held in the DB until purchased or cancelled.
     */
    public Result createSellListing(Player seller, ItemStack item, int quantity, double pricePerUnit) {
        if (pricePerUnit <= 0) return Result.INVALID_PRICE;
        if (pricePerUnit < getSellPriceFloor(item.getType())) return Result.PRICE_TOO_LOW;
        if (repo.countActiveByOwner(seller.getUniqueId()) >= getMaxListings(seller))
            return Result.MAX_LISTINGS_REACHED;

        int inInventory = countInInventory(seller, item.getType());
        if (inInventory < quantity) return Result.INSUFFICIENT_ITEMS;

        removeFromInventory(seller, item.getType(), quantity);

        ItemStack template = item.clone();
        template.setAmount(1);
        byte[] data = template.serializeAsBytes();

        PlayerListing listing = new PlayerListing(
            0, seller.getUniqueId(), seller.getName(), Type.SELL,
            item.getType().name(), displayName(item), data,
            quantity, 0, pricePerUnit, Status.ACTIVE,
            Instant.now(), Instant.now().plus(LISTING_DAYS, ChronoUnit.DAYS)
        );
        long id = repo.createListing(listing);
        return id > 0 ? Result.SUCCESS : Result.LISTING_NOT_FOUND;
    }

    /**
     * Creates a buy order.  Money is deducted from the buyer upfront (escrow).
     */
    public Result createBuyOrder(Player buyer, Material material, int quantity, double pricePerUnit) {
        if (pricePerUnit <= 0) return Result.INVALID_PRICE;
        if (pricePerUnit > getBuyPriceCeiling(material)) return Result.PRICE_TOO_HIGH;
        if (repo.countActiveByOwner(buyer.getUniqueId()) >= getMaxListings(buyer))
            return Result.MAX_LISTINGS_REACHED;

        double totalCost = pricePerUnit * quantity;
        if (!vault.has(buyer, totalCost)) return Result.INSUFFICIENT_FUNDS;

        vault.withdraw(buyer, totalCost);

        ItemStack template = new ItemStack(material, 1);
        byte[] data = template.serializeAsBytes();

        PlayerListing listing = new PlayerListing(
            0, buyer.getUniqueId(), buyer.getName(), Type.BUY,
            material.name(), material.name().replace('_', ' '), data,
            quantity, 0, pricePerUnit, Status.ACTIVE,
            Instant.now(), Instant.now().plus(LISTING_DAYS, ChronoUnit.DAYS)
        );
        long id = repo.createListing(listing);
        if (id <= 0) {
            vault.deposit(buyer, totalCost); // refund on DB failure
            return Result.LISTING_NOT_FOUND;
        }
        return Result.SUCCESS;
    }

    // ── Transact ──────────────────────────────────────────────────────────────

    /**
     * Buyer purchases {@code qty} units from a sell listing.
     * Money goes to the seller immediately; items go to the buyer's inventory
     * (overflow drops at their feet).
     */
    public Result buyFromListing(Player buyer, long listingId, int qty) {
        PlayerListing listing = repo.findById(listingId);
        if (listing == null || listing.status() != Status.ACTIVE) return Result.LISTING_NOT_FOUND;
        if (listing.listingType() != Type.SELL) return Result.LISTING_UNAVAILABLE;
        if (listing.ownerUuid().equals(buyer.getUniqueId())) return Result.CANT_TRADE_OWN_LISTING;
        if (qty > listing.quantityRemaining()) return Result.LISTING_UNAVAILABLE;

        double cost = listing.pricePerUnit() * qty;
        if (!vault.has(buyer, cost)) return Result.INSUFFICIENT_FUNDS;

        // Atomic fill (+ auto-complete) — false if someone else grabbed the stock first
        if (!repo.fillListing(listingId, qty)) return Result.LISTING_UNAVAILABLE;

        vault.withdraw(buyer, cost);
        vault.deposit(listing.ownerUuid(), cost);

        // Notify the seller above their hotbar if they're online (money already credited by UUID).
        Player onlineSeller = Bukkit.getPlayer(listing.ownerUuid());
        if (onlineSeller != null) notifyEarnings(onlineSeller, cost);

        txRepo.enqueue(new Transaction(0, buyer.getUniqueId(), listing.itemKey(),
            qty, -listing.pricePerUnit(), 1.0, -cost, Transaction.Source.PLAYER_BUY, Instant.now()));
        txRepo.enqueue(new Transaction(0, listing.ownerUuid(), listing.itemKey(),
            qty, listing.pricePerUnit(), 1.0, cost, Transaction.Source.PLAYER_SELL, Instant.now()));

        // Give items to buyer
        try {
            ItemStack give = ItemStack.deserializeBytes(listing.itemData());
            give.setAmount(qty);
            Map<Integer, ItemStack> overflow = buyer.getInventory().addItem(give);
            overflow.values().forEach(drop ->
                buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop));
        } catch (Exception e) {
            log.warning("[PlayerShop] Failed to deserialize item for listing " + listingId + ": " + e.getMessage());
        }

        return Result.SUCCESS;
    }

    /**
     * Seller fulfills {@code qty} units of a buy order.
     * Items are removed from the fulfiller's inventory; money is paid from escrow.
     * Purchased items go to the buyer's claim storage.
     */
    public Result fulfillBuyOrder(Player seller, long listingId, int qty) {
        PlayerListing listing = repo.findById(listingId);
        if (listing == null || listing.status() != Status.ACTIVE) return Result.LISTING_NOT_FOUND;
        if (listing.listingType() != Type.BUY) return Result.LISTING_UNAVAILABLE;
        if (listing.ownerUuid().equals(seller.getUniqueId())) return Result.CANT_TRADE_OWN_LISTING;
        if (qty > listing.quantityRemaining()) return Result.LISTING_UNAVAILABLE;

        Material mat = Material.matchMaterial(listing.itemKey());
        if (mat == null) return Result.LISTING_UNAVAILABLE;
        if (countInInventory(seller, mat) < qty) return Result.INSUFFICIENT_ITEMS;

        // Take the items from the seller first (game state), then atomically reserve
        // the fill and deposit the items into the buyer's storage in one transaction.
        // If the transaction can't commit, hand the seller their items back.
        removeFromInventory(seller, mat, qty);
        if (!repo.fillBuyOrderWithStorage(listingId, qty, listing.ownerUuid(), listing.itemData())) {
            ItemStack refundItems = new ItemStack(mat, qty);
            Map<Integer, ItemStack> overflow = seller.getInventory().addItem(refundItems);
            overflow.values().forEach(drop ->
                seller.getWorld().dropItemNaturally(seller.getLocation(), drop));
            return Result.LISTING_UNAVAILABLE;
        }

        double payout = listing.pricePerUnit() * qty;
        vault.deposit(seller, payout);
        notifyEarnings(seller, payout);

        txRepo.enqueue(new Transaction(0, seller.getUniqueId(), listing.itemKey(),
            qty, listing.pricePerUnit(), 1.0, payout, Transaction.Source.PLAYER_SELL, Instant.now()));
        txRepo.enqueue(new Transaction(0, listing.ownerUuid(), listing.itemKey(),
            qty, -listing.pricePerUnit(), 1.0, -payout, Transaction.Source.PLAYER_BUY, Instant.now()));

        return Result.SUCCESS;
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Cancels an active listing owned by {@code player}.
     * Sell listings: remaining items returned to seller's storage.
     * Buy orders: unfulfilled escrow money returned to buyer's balance.
     */
    public Result cancelListing(Player player, long listingId) {
        PlayerListing listing = repo.findById(listingId);
        if (listing == null) return Result.LISTING_NOT_FOUND;
        if (!listing.ownerUuid().equals(player.getUniqueId())) return Result.NOT_YOUR_LISTING;
        if (listing.status() != Status.ACTIVE) return Result.LISTING_UNAVAILABLE;

        // Atomic: flip ACTIVE→CANCELLED and (for SELL) return items to storage in one
        // transaction.  Returns the escrow refund owed for BUY orders, or 0.
        PlayerShopRepository.CloseResult result = repo.closeListing(listingId, Status.CANCELLED);
        if (!result.closed()) return Result.LISTING_UNAVAILABLE; // already closed (race)

        if (result.refund() > 0) vault.deposit(player, result.refund());
        return Result.SUCCESS;
    }

    // ── Storage / claim ───────────────────────────────────────────────────────

    public List<StorageItem> getStorage(UUID ownerUuid, int page) {
        return repo.getStorage(ownerUuid, page);
    }

    public int countStoragePages(UUID ownerUuid) {
        int total = repo.countStorage(ownerUuid);
        return Math.max(1, (int) Math.ceil(total / 36.0));
    }

    /** Claims a single storage item — moves it to the player's inventory. */
    public void claimStorageItem(Player player, StorageItem item) {
        try {
            ItemStack give = ItemStack.deserializeBytes(item.itemData());
            give.setAmount(item.quantity());
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(give);
            overflow.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));
            repo.removeStorage(item.id());
        } catch (Exception e) {
            log.warning("[PlayerShop] Failed to deserialize storage item " + item.id() + ": " + e.getMessage());
        }
    }

    /** Claims every item in the player's storage. */
    public void claimAllStorage(Player player) {
        repo.getAllStorage(player.getUniqueId()).forEach(item -> claimStorageItem(player, item));
    }

    /** True if a completed listing still has unclaimed items in storage. */
    public boolean listingHasUnclaimedItems(long listingId) {
        return repo.hasRemainingStorage(listingId);
    }

    /** Claims all items a single listing deposited into storage, straight to the player. */
    public void claimListingStorage(Player player, long listingId) {
        repo.getStorageForListing(listingId).forEach(item -> claimStorageItem(player, item));
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    public List<PlayerListing> getListings(Type type, SortOrder sort, int page) {
        return repo.findActive(type, sort, page);
    }

    public List<PlayerListing> getMyListings(UUID ownerUuid, Type type) {
        return repo.findByOwner(ownerUuid, type);
    }

    public PlayerListing getListing(long id) {
        return repo.findById(id);
    }

    // ── Expiry cleanup ────────────────────────────────────────────────────────

    /**
     * Finds all expired ACTIVE listings, processes refunds/returns, and marks
     * them EXPIRED.
     *
     * Intended to run on an async scheduler — all DB work happens off the main
     * thread.  Vault refunds for expired buy orders are hopped back to the main
     * thread, since the economy provider is only safe to call there.
     */
    public void runExpiryCleanup() {
        List<PlayerListing> expired = repo.findExpired();
        for (PlayerListing listing : expired) {
            // Atomic per listing: flip ACTIVE→EXPIRED and (for SELL) return items.
            PlayerShopRepository.CloseResult result = repo.closeListing(listing.id(), Status.EXPIRED);
            if (result.closed() && result.refund() > 0) {
                UUID owner = result.owner();
                double refund = result.refund();
                plugin.getServer().getScheduler().runTask(plugin, () -> vault.deposit(owner, refund));
            }
        }
        if (!expired.isEmpty()) {
            log.info("[PlayerShop] Expired " + expired.size() + " listing(s).");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Shows a "+$amount" message above the hotbar and plays the sell ping, like /sell. */
    private void notifyEarnings(Player player, double amount) {
        String sym = config.getCurrencySymbol();
        player.sendActionBar(messages.get(dev.smpeconomy.message.CoreKeys.SELL_EARNINGS_ACTIONBAR, TokenBag.of().put("amount", FormatUtil.formatMoney(amount, sym))));
        try {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    private int countInInventory(Player player, Material mat) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == mat) count += stack.getAmount();
        }
        return count;
    }

    private void removeFromInventory(Player player, Material mat, int amount) {
        int toRemove = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != mat) continue;
            if (stack.getAmount() <= toRemove) {
                toRemove -= stack.getAmount();
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - toRemove);
                toRemove = 0;
            }
        }
    }

    private String displayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(item.getItemMeta().displayName());
        }
        return item.getType().name().replace('_', ' ');
    }
}
