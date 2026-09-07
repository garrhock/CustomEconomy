package dev.smpeconomy.shards.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** messages.yml loader + MiniMessage sender. copyDefaults so new keys materialize on reload. */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private YamlConfiguration messages;

    public Msg(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStreamReader defaults = new InputStreamReader(
                plugin.getResource("messages.yml"), StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(defaults));
            loaded.options().copyDefaults(true);
            loaded.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not sync messages.yml defaults: " + e.getMessage());
        }
        this.messages = loaded;
    }

    public Component comp(String key, Map<String, String> tokens) {
        String raw = messages.getString(key, "<red>Missing message: " + key);
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            raw = raw.replace("{" + t.getKey() + "}", t.getValue());
        }
        return MM.deserialize(raw);
    }

    public void send(CommandSender to, String key, Map<String, String> tokens) {
        Component prefix = MM.deserialize(messages.getString("prefix", ""));
        to.sendMessage(prefix.append(comp(key, tokens)));
    }

    public void sendActionBar(org.bukkit.entity.Player to, String key, Map<String, String> tokens) {
        to.sendActionBar(comp(key, tokens));
    }
}
