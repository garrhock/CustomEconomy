package dev.smpeconomy.tooltip;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import dev.smpeconomy.api.event.PlayerSellEvent;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.service.WorthService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Appends a "Worth: $x" line to item tooltips as they are sent to the client.
 *
 * The line is injected into outbound inventory packets only — the server-side
 * ItemStack is never touched, so the text cannot leak into chests, drops,
 * trades or the world.
 *
 * Three packets carry slot contents and all of them must be handled. Missing
 * one shows up as lore that vanishes: the client predicts a click locally, and
 * whichever packet the server uses to confirm overwrites the prediction with an
 * un-injected stack. Since 1.21.2 the player's own inventory has its own packet,
 * so WINDOW_ITEMS and SET_SLOT alone are not enough.
 *
 * The cursor stack is deliberately left alone. It renders no tooltip, so there
 * is nothing to gain, and it is the stack the client echoes back most often
 * while dragging.
 *
 * Because the client predicts, a stack split still has to be corrected: the
 * predicted halves inherit the whole stack's line until the server resends.
 * That resend is debounced — see {@link RefreshDebouncer} for why it must not
 * happen while the player is still interacting.
 *
 * Threading: packet callbacks run on netty threads. Everything read from there
 * is concurrent — the worth map is volatile, multiplier and market caches are
 * ConcurrentHashMaps, and game mode / open-inventory state is mirrored into
 * concurrent collections by the Bukkit handlers rather than queried live.
 */
public final class WorthTooltipService extends PacketListenerAbstract implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final WorthService worthService;
    private final RefreshDebouncer debouncer;

    // Mirrors of main-thread state, read from netty threads.
    private final Map<UUID, InventoryType> openInventories = new ConcurrentHashMap<>();
    private final Set<UUID> creativeMode = ConcurrentHashMap.newKeySet();

    public WorthTooltipService(JavaPlugin plugin, ConfigManager config, WorthService worthService) {
        super(PacketListenerPriority.NORMAL);
        this.plugin       = plugin;
        this.config       = config;
        this.worthService = worthService;
        this.debouncer    = new RefreshDebouncer(new BukkitRefreshScheduler(plugin));
    }

    /** Bukkit-backed {@link RefreshScheduler}. */
    private record BukkitRefreshScheduler(JavaPlugin plugin) implements RefreshScheduler {
        @Override
        public Handle runLater(Runnable task, long delayTicks) {
            return Bukkit.getScheduler()
                .runTaskLater(plugin, task, Math.max(1L, delayTicks))::cancel;
        }
    }

    // ── Packet injection ─────────────────────────────────────────────────────

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!config.isWorthTooltipEnabled()) return;

        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.WINDOW_ITEMS
            && type != PacketType.Play.Server.SET_SLOT
            && type != PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            return;
        }

        UUID uuid = event.getUser().getUUID();
        if (uuid == null || !eligible(uuid)) return;

        boolean modified;
        if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            modified = injectWindowItems(new WrapperPlayServerWindowItems(event), uuid);
        } else if (type == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            ItemStack injected = withWorthLine(packet.getItem(), uuid);
            modified = injected != null;
            if (modified) packet.setItem(injected);
        } else {
            WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
            ItemStack injected = withWorthLine(packet.getStack(), uuid);
            modified = injected != null;
            if (modified) packet.setStack(injected);
        }

        if (modified) event.markForReEncode(true);
    }

    private boolean injectWindowItems(WrapperPlayServerWindowItems packet, UUID uuid) {
        List<ItemStack> items = packet.getItems();
        List<ItemStack> injected = null;

        for (int i = 0; i < items.size(); i++) {
            ItemStack withWorth = withWorthLine(items.get(i), uuid);
            if (withWorth == null) continue;
            if (injected == null) injected = new ArrayList<>(items);
            injected.set(i, withWorth);
        }

        if (injected == null) return false;
        packet.setItems(injected);
        return true;
    }

    /**
     * Returns a copy of {@code item} with the worth line appended, or null if
     * the item should be sent through unchanged.
     */
    private ItemStack withWorthLine(ItemStack item, UUID uuid) {
        if (item == null || item.isEmpty()) return null;

        String key = priceKey(item);
        double unitPrice = key == null ? -1 : worthService.getSellPrice(key, uuid);

        Component line;
        if (unitPrice > 0) {
            line = WorthTooltipFormatter.line(config.getWorthTooltipFormat(),
                config.getCurrencySymbol(), unitPrice, item.getAmount(),
                config.isWorthTooltipCompact());
        } else if (config.isWorthTooltipShowUnsellable()) {
            line = WorthTooltipFormatter.line(config.getWorthTooltipUnsellableFormat(),
                config.getCurrencySymbol(), 0, item.getAmount(),
                config.isWorthTooltipCompact());
        } else {
            return null;
        }

        ItemStack copy = item.copy();
        ItemLore existing = copy.getComponentOr(ComponentTypes.LORE, null);
        List<Component> lines = existing == null
            ? new ArrayList<>()
            : new ArrayList<>(existing.getLines());
        lines.add(line);
        copy.setComponent(ComponentTypes.LORE, new ItemLore(lines));
        return copy;
    }

    /** Lifts the values {@link WorthTooltipFormatter#priceKey} needs off the packet. */
    private String priceKey(ItemStack item) {
        String itemsAdderId = null;
        NBTCompound customData = item.getComponentOr(ComponentTypes.CUSTOM_DATA, null);
        if (customData != null) {
            itemsAdderId = customData.getStringTagValueOrNull("itemsadder:id");
        }
        return WorthTooltipFormatter.priceKey(
            item.getType().getName().getKey(),
            item.getComponentOr(ComponentTypes.CUSTOM_MODEL_DATA, null),
            item.hasComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS),
            itemsAdderId);
    }

    /**
     * Creative clients echo the stacks they were sent back to the server
     * (SetCreativeModeSlot), which would bake the injected line into the real
     * item — so creative mode is always skipped.
     */
    private boolean eligible(UUID uuid) {
        if (creativeMode.contains(uuid)) return false;
        InventoryType open = openInventories.get(uuid);
        return open == null || !config.getWorthTooltipIgnoredTypes().contains(open);
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    /**
     * Queues a resend of this player's inventory once they stop interacting,
     * so worth lines are recomputed against the stacks as they now stand.
     */
    public void queueRefresh(Player player) {
        if (!config.isWorthTooltipEnabled()) return;

        long delay = config.getWorthTooltipRefreshDelayTicks();
        if (delay < 0) return; // refresh switched off

        UUID uuid = player.getUniqueId();
        debouncer.schedule(uuid, delay, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) online.updateInventory();
        });
    }

    /**
     * Queues a refresh for every online player — for price or config changes.
     * Goes through the same debounce, so a market tick cannot land mid-drag.
     */
    public void refreshAll() {
        if (!config.isWorthTooltipEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                queueRefresh(player);
            }
        });
    }

    /** Drops every queued refresh. Called on plugin disable. */
    public void shutdown() {
        debouncer.cancelAll();
    }

    // ── Refresh triggers ─────────────────────────────────────────────────────

    // ignoreCancelled keeps GUI menus out of it: those clicks are cancelled, and
    // the menu owns what its slots show.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) queueRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) queueRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSell(PlayerSellEvent event) {
        // Selling moves the category multiplier, so every price just changed.
        queueRefresh(event.getPlayer());
    }

    // ── Main-thread state mirrors ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        openInventories.put(event.getPlayer().getUniqueId(), event.getInventory().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        openInventories.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        setCreative(event.getPlayer().getUniqueId(), event.getNewGameMode() == GameMode.CREATIVE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        setCreative(player.getUniqueId(), player.getGameMode() == GameMode.CREATIVE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openInventories.remove(uuid);
        creativeMode.remove(uuid);
        debouncer.cancel(uuid);
    }

    private void setCreative(UUID uuid, boolean creative) {
        if (creative) creativeMode.add(uuid);
        else          creativeMode.remove(uuid);
    }
}
