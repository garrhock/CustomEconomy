package dev.smpeconomy.shards.listener;

import dev.smpeconomy.shards.service.ShardsService;
import dev.smpeconomy.shards.util.EarnFeedback;
import dev.smpeconomy.shards.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Join/quit cache lifecycle + the PvP-kill earn path. */
public final class ShardListeners implements Listener {

    private final JavaPlugin plugin;
    private final ShardsService service;
    private final Msg msg;
    private final EarnFeedback feedback;

    /** killer|victim pair → epoch millis the cooldown expires. */
    private final Map<String, Long> killCooldowns = new ConcurrentHashMap<>();

    public ShardListeners(JavaPlugin plugin, ShardsService service, Msg msg, EarnFeedback feedback) {
        this.plugin = plugin;
        this.service = service;
        this.msg = msg;
        this.feedback = feedback;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.unloadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        long amount = plugin.getConfig().getLong("shards.earn.kill.amount", 10);
        if (amount <= 0) {
            return;
        }
        long cooldownMs = plugin.getConfig().getLong("shards.earn.kill.same-victim-cooldown-minutes", 30) * 60_000L;
        if (cooldownMs > 0) {
            String pair = killer.getUniqueId() + "|" + victim.getUniqueId();
            long now = System.currentTimeMillis();
            Long expires = killCooldowns.get(pair);
            if (expires != null && expires > now) {
                msg.sendActionBar(killer, "actionbar-cooldown", Map.of("victim", victim.getName()));
                return;
            }
            killCooldowns.put(pair, now + cooldownMs);
            killCooldowns.values().removeIf(v -> v < now);
        }
        UUID killerId = killer.getUniqueId();
        service.deposit(killerId, amount);
        msg.sendActionBar(killer, "actionbar-kill", Map.of(
                "amount", String.valueOf(amount),
                "victim", victim.getName()));
        feedback.play(killer);
    }
}
