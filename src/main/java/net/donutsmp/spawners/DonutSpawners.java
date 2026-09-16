package net.donutsmp.spawners;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.message.Messages;

import net.donutsmp.spawners.commands.GiveSpawnerCommand;
import net.donutsmp.spawners.economy.EconomyHandler;
import net.donutsmp.spawners.listeners.SpawnerListener;
import net.donutsmp.spawners.storage.SpawnerManager;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

/**
 * Virtual spawners, vendored from DonutSMP-Remake/DonutSpawner (MIT) and merged in so
 * spawner income shares one economy and one jar.
 *
 * This was a JavaPlugin upstream, so it still exposes getConfig()/getLogger()/getDataFolder()
 * and the vendored classes didn't have to change. Config comes from spawners.yml, and
 * bukkit() hands out the real plugin for the APIs that need one.
 */
public class DonutSpawners {

    private static DonutSpawners instance;

    private final JavaPlugin plugin;
    private final File configFile;
    private FileConfiguration config;

    private SpawnerManager spawnerManager;
    private EconomyHandler economyHandler;

    public DonutSpawners(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "spawners.yml");
    }

    public void enable() {
        instance = this;

        if (!configFile.exists()) {
            plugin.saveResource("spawners.yml", false);
        }
        reloadConfig();

        spawnerManager = new SpawnerManager(this);
        spawnerManager.loadSpawners();

        economyHandler = new EconomyHandler(this);

        plugin.getServer().getPluginManager().registerEvents(new SpawnerListener(this), plugin);
        PluginCommand give = plugin.getCommand("givespawner");
        if (give != null) {
            give.setExecutor(new GiveSpawnerCommand(this));
        }

        plugin.getLogger().info("Spawners enabled — " + spawnerManager.getSpawners().size() + " tracked.");
    }

    public void disable() {
        if (spawnerManager != null) {
            spawnerManager.saveSpawners();
        }
    }

    public void reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    // vendored classes call this exactly like they did when this was a JavaPlugin
    public FileConfiguration getConfig() {
        return config;
    }

    /** The owning plugin, for Bukkit APIs that need a real Plugin. */
    public JavaPlugin bukkit() {
        return plugin;
    }

    public File getDataFolder() {
        return plugin.getDataFolder();
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    public Server getServer() {
        return plugin.getServer();
    }

    public static DonutSpawners getInstance() {
        return instance;
    }

    public Messages messages() {
        return CustomEconomy.getInstance().getMessages();
    }

    public SpawnerManager getSpawnerManager() {
        return spawnerManager;
    }

    public EconomyHandler getEconomyHandler() {
        return economyHandler;
    }
}
