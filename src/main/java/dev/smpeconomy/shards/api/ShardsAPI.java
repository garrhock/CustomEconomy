package dev.smpeconomy.shards.api;

import dev.smpeconomy.shards.service.ShardsService;

import java.util.UUID;

/**
 * Public surface for other plugins (spawner shop, KOTH, crates).
 * Mirrors the CustomEconomyAPI pattern: static accessor, no compile-time
 * dependency needed — consumers may also reach it by reflection.
 */
public final class ShardsAPI {

    private static ShardsService service;

    private ShardsAPI() {
    }

    public static void init(ShardsService s) {
        service = s;
    }

    public static long getBalance(UUID player) {
        return service == null ? 0L : service.balance(player);
    }

    public static long deposit(UUID player, long amount) {
        return service == null ? 0L : service.deposit(player, amount);
    }

    public static boolean withdraw(UUID player, long amount) {
        return service != null && service.withdraw(player, amount);
    }
}
