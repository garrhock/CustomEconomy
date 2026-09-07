package dev.smpeconomy.service;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Captures a single chat message from a player and routes it to a callback.
 *
 * Usage:
 *   chatInputService.prompt(player, "Enter item name:", input -> { ... });
 *
 * The chat message is cancelled (not broadcast). If the player types "cancel"
 * (case-insensitive) or the 30-second window expires, the pending input is
 * discarded silently.  The callback always runs on the main server thread.
 */
public final class ChatInputService implements Listener {

    private static final long TIMEOUT_MS = 30_000;

    private record PendingInput(Consumer<String> callback, long expiresAt) {}

    private final ConcurrentHashMap<UUID, PendingInput> pending = new ConcurrentHashMap<>();
    private final JavaPlugin plugin;

    public ChatInputService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends {@code promptMessage} to the player and waits for their next chat line.
     * Automatically appends a "(30s • type 'cancel' to abort)" hint.
     */
    public void prompt(Player player, String promptMessage, Consumer<String> callback) {
        long expires = System.currentTimeMillis() + TIMEOUT_MS;
        pending.put(player.getUniqueId(), new PendingInput(callback, expires));
        player.sendMessage(net.kyori.adventure.text.Component.text(
            promptMessage + "  §7(30s — type 'cancel' to abort)")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
    }

    /** Cancels a pending input for the given player (e.g. when they close a GUI). */
    public void cancel(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean hasPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        PendingInput pi = pending.remove(event.getPlayer().getUniqueId());
        if (pi == null) return;

        event.setCancelled(true);

        if (System.currentTimeMillis() > pi.expiresAt()) return;

        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (input.equalsIgnoreCase("cancel")) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> pi.callback().accept(input));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
