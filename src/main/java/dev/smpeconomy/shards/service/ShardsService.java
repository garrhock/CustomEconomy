package dev.smpeconomy.shards.service;

import dev.smpeconomy.shards.storage.ShardStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Balance authority. All mutations go through atomic cache ops
 * (ConcurrentHashMap.compute) and mirror to SQLite asynchronously.
 */
public final class ShardsService {

    private final ShardStore store;
    private final ConcurrentHashMap<UUID, Long> cache = new ConcurrentHashMap<>();

    public ShardsService(ShardStore store) {
        this.store = store;
    }

    public void loadPlayer(UUID uuid) {
        store.loadAsync(uuid, balance -> cache.putIfAbsent(uuid, balance));
    }

    public void unloadPlayer(UUID uuid) {
        Long balance = cache.remove(uuid);
        if (balance != null) {
            store.saveAsync(uuid, balance);
        }
    }

    public long balance(UUID uuid) {
        return cache.getOrDefault(uuid, 0L);
    }

    public long deposit(UUID uuid, long amount) {
        long updated = cache.compute(uuid, (k, v) -> (v == null ? 0L : v) + amount);
        store.saveAsync(uuid, updated);
        return updated;
    }

    /** @return true if the balance covered the amount (and was deducted). */
    public boolean withdraw(UUID uuid, long amount) {
        AtomicBoolean ok = new AtomicBoolean(false);
        long updated = cache.compute(uuid, (k, v) -> {
            long current = v == null ? 0L : v;
            if (current < amount) {
                return current;
            }
            ok.set(true);
            return current - amount;
        });
        if (ok.get()) {
            store.saveAsync(uuid, updated);
        }
        return ok.get();
    }

    public long set(UUID uuid, long amount) {
        long updated = Math.max(0L, amount);
        cache.put(uuid, updated);
        store.saveAsync(uuid, updated);
        return updated;
    }
}
