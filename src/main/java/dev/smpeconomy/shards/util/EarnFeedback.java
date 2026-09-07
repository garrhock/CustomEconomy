package dev.smpeconomy.shards.util;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** The earn "ding" — config-driven sound played alongside the action bar. */
public final class EarnFeedback {

    private final JavaPlugin plugin;

    public EarnFeedback(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void play(Player player) {
        String name = plugin.getConfig().getString("shards.feedback.sound", "BLOCK_AMETHYST_BLOCK_CHIME");
        float volume = (float) plugin.getConfig().getDouble("shards.feedback.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("shards.feedback.pitch", 1.4);
        try {
            player.playSound(player.getLocation(), Sound.valueOf(name.toUpperCase()), volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown feedback.sound '" + name + "' — no sound played.");
        }
    }
}
