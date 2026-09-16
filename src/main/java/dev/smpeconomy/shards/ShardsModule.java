package dev.smpeconomy.shards;

import dev.smpeconomy.CustomEconomy;

import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.database.DatabaseManager;
import dev.smpeconomy.shards.api.ShardsAPI;
import dev.smpeconomy.shards.command.ShardsAdminCommand;
import dev.smpeconomy.shards.command.ShardsCommand;
import dev.smpeconomy.shards.gui.ShardShop;
import dev.smpeconomy.shards.listener.ShardListeners;
import dev.smpeconomy.shards.papi.ShardsExpansion;
import dev.smpeconomy.shards.service.ShardsService;
import dev.smpeconomy.shards.storage.LegacyShardImport;
import dev.smpeconomy.shards.storage.ShardStore;
import dev.smpeconomy.shards.util.EarnFeedback;
import dev.smpeconomy.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * The shard currency, wired as a subsystem of CustomEconomy.
 *
 * Shards were a separate plugin until the merge; everything they own — the
 * balance cache, the shop, the PvP earn path — still lives under this package,
 * but the connection pool, config file, and plugin lifecycle are now shared
 * with the money economy.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ShardsModule {

    private final JavaPlugin plugin;
    private final DatabaseManager db;
    private final ConfigManager config;

    private ShardStore store;
    private ShardsService service;
    private Messages msg;
    private ShardShop shop;

    public ShardsModule(JavaPlugin plugin, DatabaseManager db, ConfigManager config) {
        this.plugin = plugin;
        this.db     = db;
        this.config = config;
    }

    public void enable() {
        msg   = CustomEconomy.getInstance().getMessages();
        store = new ShardStore(db, config.isMysql(), plugin.getLogger());

        LegacyShardImport.run(plugin.getDataFolder(), db, config.isMysql(), plugin.getLogger());

        service = new ShardsService(store);
        ShardsAPI.init(service);

        // Handles /reload-style enables while players are already online
        for (Player online : Bukkit.getOnlinePlayers()) {
            service.loadPlayer(online.getUniqueId());
        }

        EarnFeedback feedback = new EarnFeedback(plugin);
        shop = new ShardShop(plugin, service, msg, feedback);

        Bukkit.getPluginManager().registerEvents(new ShardListeners(plugin, service, msg, feedback), plugin);
        Bukkit.getPluginManager().registerEvents(shop, plugin);

        Objects.requireNonNull(plugin.getCommand("shards"))
                .setExecutor(new ShardsCommand(service, msg));
        Objects.requireNonNull(plugin.getCommand("shardsadmin"))
                .setExecutor(new ShardsAdminCommand(config, service, msg, this, feedback));
        Objects.requireNonNull(plugin.getCommand("shardshop"))
                .setExecutor((sender, command, label, args) -> {
                    if (sender instanceof Player player) {
                        shop.open(player);
                    }
                    return true;
                });

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new ShardsExpansion(service, plugin.getPluginMeta().getVersion()).register();
            plugin.getLogger().info("Shards PlaceholderAPI expansion registered (%shards_balance%).");
        }

        plugin.getLogger().info("Shards enabled — kill reward: "
                + plugin.getConfig().getLong("shards.earn.kill.amount", 10) + " shards.");
    }

    /** Re-reads messages.yml and the shop section. config.yml itself is reloaded by ConfigManager. */
    public void reload() {
        // /ecoadmin reload already reloaded messages.yml; just rebuild the shop
        if (shop != null) shop.reload();
    }

    /** Flushes every online balance, then drains queued writes. Runs before the pool closes. */
    public void disable() {
        if (service != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                service.unloadPlayer(online.getUniqueId());
            }
        }
        if (store != null) {
            store.shutdown();
        }
    }

    public ShardsService getService() { return service; }
}
