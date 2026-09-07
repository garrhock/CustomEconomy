package dev.smpeconomy.gui;

import dev.smpeconomy.CustomEconomy;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class BaseGui implements InventoryHolder {

    private static final long CLICK_COOLDOWN_MS = 250;

    protected final Inventory inventory;
    /** The player this menu was opened for. Set by {@link #open(Player)} before populate(). */
    protected Player viewer;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private final Map<UUID, Long> lastClickMs = new HashMap<>();

    /** Bumped on every {@link #asyncLoad} call so stale results can be discarded. */
    private int loadGeneration = 0;

    protected BaseGui(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    protected void onClick(int slot, Consumer<InventoryClickEvent> handler) {
        handlers.put(slot, handler);
    }

    /**
     * Removes all registered click handlers.
     * Call this at the top of {@link #populate()} in menus that re-render the
     * same instance (pagination, toggles) so stale handlers can't fire on slots
     * that are no longer interactive.
     */
    protected void clearHandlers() {
        handlers.clear();
    }

    /**
     * Runs a blocking data load off the main thread, then applies the result back
     * on the main thread — but only if this menu is still the relevant one.
     *
     * The result is discarded if: a newer {@code asyncLoad} has started on this menu
     * (pagination/toggle), the viewer logged out, or the viewer no longer has this
     * exact menu open.  This prevents a slow query from painting a screen the player
     * has already navigated away from, and keeps all Bukkit/inventory mutation on the
     * main thread.
     *
     * @param loader blocking work (e.g. a DB query) — runs async, must not touch Bukkit
     * @param render applies the result to the inventory — runs on the main thread
     */
    protected <T> void asyncLoad(Supplier<T> loader, Consumer<T> render) {
        final int gen = ++loadGeneration;
        final JavaPlugin plugin = CustomEconomy.getInstance();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final T data = loader.get();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (gen != loadGeneration) return;                 // superseded by a newer load
                if (viewer == null || !viewer.isOnline()) return;  // viewer gone
                if (viewer.getOpenInventory().getTopInventory().getHolder() != this) return; // navigated away
                render.accept(data);
            });
        });
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == player.getInventory()) return;
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Long last = lastClickMs.get(player.getUniqueId());
        if (last != null && now - last < CLICK_COOLDOWN_MS) return;
        lastClickMs.put(player.getUniqueId(), now);

        Consumer<InventoryClickEvent> handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            if (playClickSound()) {
                // Play before the handler runs so it fires even when the handler
                // immediately opens/closes another inventory.
                try { player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f); }
                catch (Throwable ignored) {}
            }
            handler.accept(event);
        }
    }

    public void open(Player player) {
        this.viewer = player;
        populate();
        player.openInventory(inventory);
        if (playOpenSound()) {
            try { player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.5f, 1.4f); }
            catch (Throwable ignored) {}
        }
    }

    /** Override to mute or change the centralized UI click sound for a menu. */
    protected boolean playClickSound() { return true; }

    /** Override to mute the soft open sound. Fires only on open(), not on re-renders. */
    protected boolean playOpenSound() { return true; }

    protected abstract void populate();
}
