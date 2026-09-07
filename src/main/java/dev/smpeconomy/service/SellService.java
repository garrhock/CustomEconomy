package dev.smpeconomy.service;

import dev.smpeconomy.api.event.PlayerSellEvent;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.repository.TransactionRepository;
import dev.smpeconomy.hook.VaultHook;
import dev.smpeconomy.model.ItemCategory;
import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.model.SellResult;
import dev.smpeconomy.model.Transaction;
import dev.smpeconomy.util.FormatUtil;
import dev.smpeconomy.util.ItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core sell logic.
 *
 * All public methods must be called from the main thread.
 *
 * Pipeline:
 * 1. Scan items — no side effects.
 * 2. Build SellResult with per-category multipliers baked into priceEach.
 * 3. Fire PlayerSellEvent (cancellable).
 * 4. Remove items from inventory.
 * 5. Deposit money via Vault.
 * 6. Award category XP; notify player of any level-ups.
 * 7. Enqueue transactions for async DB write.
 */
public final class SellService {

    private static final TextColor GOLD = TextColor.color(0xFFAA00);
    private static final TextColor GREEN = TextColor.color(0x55FF55);

    private final WorthService worth;
    private final VaultHook vault;
    private final TransactionRepository txRepo;
    private final ConfigManager config;
    private final MultiplierService multiplierService;
    private final MarketService marketService;

    public SellService(WorthService worth, VaultHook vault,
            TransactionRepository txRepo, ConfigManager config,
            MultiplierService multiplierService, MarketService marketService) {
        this.worth = worth;
        this.vault = vault;
        this.txRepo = txRepo;
        this.config = config;
        this.multiplierService = multiplierService;
        this.marketService = marketService;
    }

    /**
     * Effective sell price per unit = base × player category multiplier × market factor.
     * Keeping this in one place ensures the money paid matches what /worths, /worth and
     * the sell GUI display.
     */
    private double sellPrice(ItemWorth w, double mult) {
        return w.getBasePrice() * mult * marketService.getFactor(w.getKey());
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public SellResult sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!ItemUtil.isValid(hand))
            return SellResult.empty();
        return sell(player, new ItemStack[] { hand }, Transaction.Source.SELL_HAND);
    }

    public SellResult sellInventory(Player player) {
        return sell(player, player.getInventory().getContents(), Transaction.Source.SELL_INVENTORY);
    }

    public SellResult sellItems(Player player, ItemStack[] items, Transaction.Source source) {
        return sell(player, items, source);
    }

    /**
     * Sells items that came from outside the player's inventory (e.g. a sell GUI
     * staging area).
     * Does NOT remove anything from the player's inventory — the caller must clear
     * the source.
     * Unsellable items are returned in {@link SellResult#getReturned()}.
     */
    public SellResult sellExternal(Player player, ItemStack[] items) {
        if (!vault.isHooked()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            return SellResult.empty();
        }

        record SellEntry(ItemWorth itemWorth, int qty, double priceEach, double appliedMult) {
        }
        java.util.List<SellEntry> entries = new ArrayList<>();
        java.util.List<ItemStack> unsellable = new ArrayList<>();

        // Expand shulker boxes: sell their contents, return the empty box to the player
        java.util.List<ItemStack> flatItems = new ArrayList<>();
        java.util.List<ItemStack> emptyShulkers = new ArrayList<>();
        for (ItemStack item : items) {
            if (!ItemUtil.isValid(item)) continue;
            if (ItemUtil.isShulkerBox(item.getType())) {
                java.util.List<ItemStack> contents = ItemUtil.getShulkerContents(item);
                if (!contents.isEmpty()) {
                    flatItems.addAll(contents);
                    emptyShulkers.add(ItemUtil.emptyShulkerCopy(item));
                    continue;
                }
            }
            flatItems.add(item);
        }

        for (ItemStack item : flatItems) {
            if (!ItemUtil.isValid(item))
                continue;
            java.util.Optional<ItemWorth> opt = worth.getItemWorth(item);
            if (opt.isEmpty()) {
                unsellable.add(item);
                continue;
            }
            ItemWorth w = opt.get();
            double mult = multiplierService.getMultiplier(player.getUniqueId(), w.getCategory());
            double price = sellPrice(w, mult);
            entries.add(new SellEntry(w, item.getAmount(), price, mult));
        }

        SellResult.Builder rb = SellResult.builder();
        emptyShulkers.forEach(rb::addReturned);
        unsellable.forEach(rb::addReturned);

        if (entries.isEmpty()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            return rb.build();
        }

        java.util.List<Transaction> transactions = new ArrayList<>(entries.size());
        java.time.Instant now = java.time.Instant.now();
        for (SellEntry e : entries) {
            rb.addLine(e.itemWorth().getKey(), e.itemWorth().getDisplayName(), e.qty(), e.priceEach());
            transactions.add(new Transaction(
                    0, player.getUniqueId(), e.itemWorth().getKey(),
                    e.qty(), e.priceEach(), e.appliedMult(),
                    e.qty() * e.priceEach(),
                    Transaction.Source.SELL_GUI, now));
        }

        SellResult result = rb.build();
        if (!result.isSuccess())
            return result;

        dev.smpeconomy.api.event.PlayerSellEvent event = new dev.smpeconomy.api.event.PlayerSellEvent(player, result,
                transactions);
        player.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            return SellResult.empty();
        }

        vault.deposit(player, result.getTotalEarned());

        // Play success sound (note block harp ping) for successful sell
        try {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // ignore
        }
        Map<ItemCategory, Long> xpByCategory = new EnumMap<>(ItemCategory.class);
        for (SellEntry e : entries) {
            long xp = (long) (e.itemWorth().getXpPerUnit() * e.qty());
            xpByCategory.merge(e.itemWorth().getCategory(), xp, Long::sum);
        }

        String sym = config.getCurrencySymbol();
        for (Map.Entry<ItemCategory, Long> xpEntry : xpByCategory.entrySet()) {
            int newLevel = multiplierService.addXp(
                    player.getUniqueId(), xpEntry.getKey(), xpEntry.getValue());
            if (newLevel >= 0) {
                double newMult = MultiplierService.multiplierForLevel(newLevel);
                player.sendMessage(
                        Component.text("★ ", GOLD)
                                .append(Component.text(xpEntry.getKey().getDisplayName() + " Level Up! ", GREEN))
                                .append(Component.text("Level " + newLevel + " — Sell bonus: "
                                        + FormatUtil.formatMultiplier(newMult), GOLD)));
            }
        }

        for (Transaction tx : transactions)
            txRepo.enqueue(tx);
        return result;
    }

    // ── Core pipeline ────────────────────────────────────────────────────────

    private SellResult sell(Player player, ItemStack[] items, Transaction.Source source) {
        if (!vault.isHooked()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            return SellResult.empty();
        }

        // ── Step 1: scan (expand shulker boxes into their contents) ──────────
        // stack == null means the item came from inside a shulker and is not
        // directly in the player's inventory — removeItem must not be called for it.
        record SellEntry(ItemStack stack, ItemWorth itemWorth, int qty,
                double priceEach, double appliedMult) {
        }
        List<SellEntry> entries = new ArrayList<>();
        List<ItemStack> shulkerBoxRefs = new ArrayList<>(); // originals to remove + return empty

        for (ItemStack item : items) {
            if (!ItemUtil.isValid(item))
                continue;

            if (ItemUtil.isShulkerBox(item.getType())) {
                List<ItemStack> contents = ItemUtil.getShulkerContents(item);
                if (!contents.isEmpty()) {
                    // Sell contents; the shulker box itself is removed and returned empty
                    shulkerBoxRefs.add(item);
                    for (ItemStack inner : contents) {
                        Optional<ItemWorth> opt = worth.getItemWorth(inner);
                        if (opt.isEmpty()) continue;
                        ItemWorth w = opt.get();
                        double mult = multiplierService.getMultiplier(player.getUniqueId(), w.getCategory());
                        entries.add(new SellEntry(null, w, inner.getAmount(), sellPrice(w, mult), mult));
                    }
                    continue;
                }
                // Empty shulker box — fall through and try to sell it as a normal item
            }

            Optional<ItemWorth> opt = worth.getItemWorth(item);
            if (opt.isEmpty())
                continue;
            ItemWorth w = opt.get();
            double mult = multiplierService.getMultiplier(player.getUniqueId(), w.getCategory());
            double price = sellPrice(w, mult);
            entries.add(new SellEntry(item, w, item.getAmount(), price, mult));
        }

        if (entries.isEmpty()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            return SellResult.empty();
        }

        // ── Step 2: build result ──────────────────────────────────────────────
        // multiplier stays at default 1.0 — it's already baked into priceEach
        SellResult.Builder rb = SellResult.builder();
        List<Transaction> transactions = new ArrayList<>(entries.size());
        Instant now = Instant.now();

        for (SellEntry e : entries) {
            rb.addLine(e.itemWorth().getKey(), e.itemWorth().getDisplayName(), e.qty(), e.priceEach());
            transactions.add(new Transaction(
                    0, player.getUniqueId(), e.itemWorth().getKey(),
                    e.qty(), e.priceEach(), e.appliedMult(),
                    e.qty() * e.priceEach(),
                    source, now));
        }
        SellResult result = rb.build();

        if (!result.isSuccess())
            return result;

        // ── Step 3: fire event (cancellable) ─────────────────────────────────
        PlayerSellEvent event = new PlayerSellEvent(player, result, transactions);
        player.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            return SellResult.empty();
        }

        // ── Step 4: remove items ──────────────────────────────────────────────
        // Items from shulker contents (stack == null) are not in the player's
        // inventory — remove only the shulker box itself, then return it empty.
        for (SellEntry e : entries) {
            if (e.stack() != null) removeItem(player, e.stack());
        }
        for (ItemStack shulker : shulkerBoxRefs) {
            removeItem(player, shulker);
            ItemStack empty = ItemUtil.emptyShulkerCopy(shulker);
            java.util.Map<Integer, ItemStack> overflow = player.getInventory().addItem(empty);
            overflow.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }

        // ── Step 5: deposit money ─────────────────────────────────────────────
        vault.deposit(player, result.getTotalEarned());

        // ── Step 6: award XP and notify on level-up ──────────────────────────
        Map<ItemCategory, Long> xpByCategory = new EnumMap<>(ItemCategory.class);
        for (SellEntry e : entries) {
            long xp = (long) (e.itemWorth().getXpPerUnit() * e.qty());
            xpByCategory.merge(e.itemWorth().getCategory(), xp, Long::sum);
        }

        String sym = config.getCurrencySymbol();
        for (Map.Entry<ItemCategory, Long> xpEntry : xpByCategory.entrySet()) {
            int newLevel = multiplierService.addXp(
                    player.getUniqueId(), xpEntry.getKey(), xpEntry.getValue());
            if (newLevel >= 0) {
                double newMult = MultiplierService.multiplierForLevel(newLevel);
                player.sendMessage(
                        Component.text("★ ", GOLD)
                                .append(Component.text(xpEntry.getKey().getDisplayName() + " Level Up! ", GREEN))
                                .append(Component.text("Level " + newLevel + " — Sell bonus: "
                                        + FormatUtil.formatMultiplier(newMult), GOLD)));
            }
        }

        // ── Step 7: async DB write ────────────────────────────────────────────
        for (Transaction tx : transactions)
            txRepo.enqueue(tx);

        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Removes exactly {@code target.getAmount()} of {@code target} from the player.
     *
     * Scans the full inventory — storage (0-35), armor (36-39) AND offhand (40) —
     * matching by {@link ItemStack#isSimilar}, and clears/decrements matching slots.
     * The older approach relied on {@code PlayerInventory.removeItem}, which only
     * touches main storage and silently left armor/offhand items behind — the player
     * was paid for them but kept them (a dupe). Iterating getContents() and using
     * setItem covers the equipment slots too.
     */
    private void removeItem(Player player, ItemStack target) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        ItemStack match = target.clone(); // stable snapshot; target may be a live slot mirror
        int remaining = match.getAmount();

        ItemStack[] contents = inv.getContents(); // 41 slots: storage + armor + offhand
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack slot = contents[i];
            if (slot == null || slot.getType().isAir() || !slot.isSimilar(match)) continue;

            int amt = slot.getAmount();
            if (amt <= remaining) {
                inv.setItem(i, null);
                remaining -= amt;
            } else {
                slot.setAmount(amt - remaining);
                inv.setItem(i, slot);
                remaining = 0;
            }
        }
    }
}
