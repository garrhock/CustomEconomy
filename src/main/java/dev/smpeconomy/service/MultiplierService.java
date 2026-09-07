package dev.smpeconomy.service;

import dev.smpeconomy.database.repository.ProgressionRepository;
import dev.smpeconomy.model.ItemCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player, per-category sell multipliers.
 *
 * Selling items in a category earns XP for that category.
 * XP accumulates to raise the category level, which raises the multiplier.
 *
 * Math:
 *   totalXpForLevel(n) = n² × XP_SCALE
 *   level(totalXp)     = min(floor(√(totalXp / XP_SCALE)), MAX_LEVEL)
 *   multiplier(level)  = min(1.0 + level × 0.1, 3.0)
 *
 * 20 stages: 1.1× at level 1 → 3.0× at level 20.
 */
public final class MultiplierService {

    public static final int    XP_SCALE  = 25_000;
    public static final int    MAX_LEVEL = 20;
    public static final double MAX_MULT  = 3.0;

    private final ProgressionRepository repo;

    // In-memory cache: uuid → (category → totalXp)
    private final ConcurrentHashMap<UUID, Map<ItemCategory, Long>> cache = new ConcurrentHashMap<>();

    public MultiplierService(ProgressionRepository repo) {
        this.repo = repo;
    }

    // ── Player lifecycle ─────────────────────────────────────────────────────

    /** Called on player join — loads their progression from the DB asynchronously. */
    public void loadPlayer(UUID uuid) {
        repo.load(uuid).thenAccept(data -> {
            Map<ItemCategory, Long> map = new EnumMap<>(ItemCategory.class);
            map.putAll(data);
            cache.put(uuid, map);
        });
    }

    /** Called on player quit — saves to DB and removes from cache. */
    public void evictPlayer(UUID uuid) {
        Map<ItemCategory, Long> data = cache.remove(uuid);
        if (data != null && !data.isEmpty()) {
            repo.save(uuid, data, MultiplierService::levelFor, MultiplierService::multiplierForLevel);
        }
    }

    /** Saves all online players' progression to DB (called on periodic flush and shutdown). */
    public void saveAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<UUID, Map<ItemCategory, Long>> entry : cache.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                futures.add(repo.save(entry.getKey(), entry.getValue(),
                    MultiplierService::levelFor, MultiplierService::multiplierForLevel));
            }
        }
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    // ── XP and multiplier queries ────────────────────────────────────────────

    public double getMultiplier(UUID uuid, ItemCategory category) {
        return multiplierForLevel(getLevel(uuid, category));
    }

    public int getLevel(UUID uuid, ItemCategory category) {
        return levelFor(getXp(uuid, category));
    }

    public long getXp(UUID uuid, ItemCategory category) {
        Map<ItemCategory, Long> map = cache.get(uuid);
        return map == null ? 0L : map.getOrDefault(category, 0L);
    }

    /** XP earned within the current level (for progress-bar displays). */
    public long getXpInLevel(UUID uuid, ItemCategory category) {
        long xp    = getXp(uuid, category);
        int  level = levelFor(xp);
        return xp - xpForLevel(level);
    }

    /** XP needed to reach the next level from the current XP total. */
    public long getXpToNextLevel(UUID uuid, ItemCategory category) {
        long xp    = getXp(uuid, category);
        int  level = levelFor(xp);
        if (level >= MAX_LEVEL) return 0L;
        return xpForLevel(level + 1) - xp;
    }

    // ── XP award ────────────────────────────────────────────────────────────

    /**
     * Adds XP to a category for a player.
     *
     * @return the new level if the player leveled up, -1 if they did not.
     */
    public int addXp(UUID uuid, ItemCategory category, long xpGained) {
        Map<ItemCategory, Long> map = cache.computeIfAbsent(uuid, k -> new EnumMap<>(ItemCategory.class));

        long oldXp   = map.getOrDefault(category, 0L);
        int  oldLevel = levelFor(oldXp);

        long cap    = xpForLevel(MAX_LEVEL);
        long newXp  = Math.min(oldXp + xpGained, cap);
        int  newLevel = levelFor(newXp);

        map.put(category, newXp);
        return newLevel > oldLevel ? newLevel : -1;
    }

    // ── Static math helpers ──────────────────────────────────────────────────

    public static int levelFor(long totalXp) {
        if (totalXp <= 0) return 0;
        return Math.min((int) Math.sqrt((double) totalXp / XP_SCALE), MAX_LEVEL);
    }

    public static long xpForLevel(int level) {
        return (long) level * level * XP_SCALE;
    }

    public static double multiplierForLevel(int level) {
        return Math.min(1.0 + level * 0.1, MAX_MULT);
    }
}
