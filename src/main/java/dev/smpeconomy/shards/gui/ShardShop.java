package dev.smpeconomy.shards.gui;

import dev.smpeconomy.shards.service.ShardsService;
import dev.smpeconomy.shards.util.EarnFeedback;
import dev.smpeconomy.shards.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config-driven shard shop. Two-click confirm; purchases dispatch console
 * commands so item delivery (SilkSpawners etc.) needs no compile dependency.
 */
public final class ShardShop implements Listener {

    private record Entry(String id, int slot, Material icon, String name, long cost, List<String> commands) {
    }

    /** Marks our inventory so the click handler can't collide with other GUIs. */
    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private record PendingBuy(String entryId, long expiresAt) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long CONFIRM_WINDOW_MS = 10_000L;

    private final JavaPlugin plugin;
    private final ShardsService service;
    private final Msg msg;
    private final EarnFeedback feedback;

    private String title = "Shard Shop";
    private int rows = 3;
    private final Map<Integer, Entry> bySlot = new HashMap<>();
    private final Map<UUID, PendingBuy> pending = new ConcurrentHashMap<>();

    public ShardShop(JavaPlugin plugin, ShardsService service, Msg msg, EarnFeedback feedback) {
        this.plugin = plugin;
        this.service = service;
        this.msg = msg;
        this.feedback = feedback;
        reload();
    }

    public void reload() {
        bySlot.clear();
        var shop = plugin.getConfig().getConfigurationSection("shards.shop");
        if (shop == null) {
            return;
        }
        this.title = shop.getString("title", "Shard Shop");
        this.rows = Math.max(1, Math.min(6, shop.getInt("rows", 3)));
        var entries = shop.getConfigurationSection("entries");
        if (entries == null) {
            return;
        }
        for (String id : entries.getKeys(false)) {
            var section = entries.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            Material icon = Material.matchMaterial(section.getString("icon", "SPAWNER"));
            Entry entry = new Entry(id,
                    section.getInt("slot", 0),
                    icon == null ? Material.SPAWNER : icon,
                    section.getString("name", id),
                    section.getLong("cost", 0),
                    section.getStringList("commands"));
            bySlot.put(entry.slot(), entry);
        }
    }

    public void open(Player player) {
        ShopHolder holder = new ShopHolder();
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, MM.deserialize(title));
        holder.inventory = inventory;
        for (Entry entry : bySlot.values()) {
            if (entry.slot() < 0 || entry.slot() >= rows * 9) {
                continue;
            }
            ItemStack item = new ItemStack(entry.icon());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(MM.deserialize(entry.name()).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MM.deserialize("<gray>Cost</gray> <dark_gray>»</dark_gray> <aqua>"
                    + entry.cost() + " ⧫</aqua>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<yellow>Click twice to purchase</yellow>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
            inventory.setItem(entry.slot(), item);
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Entry entry = bySlot.get(event.getRawSlot());
        if (entry == null) {
            return;
        }
        long now = System.currentTimeMillis();
        PendingBuy buy = pending.get(player.getUniqueId());
        if (buy == null || !buy.entryId().equals(entry.id()) || buy.expiresAt() < now) {
            pending.put(player.getUniqueId(), new PendingBuy(entry.id(), now + CONFIRM_WINDOW_MS));
            msg.send(player, "shop-confirm", Map.of(
                    "item", entry.name(),
                    "cost", String.valueOf(entry.cost())));
            return;
        }
        pending.remove(player.getUniqueId());
        if (!service.withdraw(player.getUniqueId(), entry.cost())) {
            msg.send(player, "shop-insufficient", Map.of(
                    "cost", String.valueOf(entry.cost()),
                    "balance", String.valueOf(service.balance(player.getUniqueId()))));
            return;
        }
        for (String command : entry.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    command.replace("%player%", player.getName()));
        }
        msg.send(player, "shop-bought", Map.of(
                "item", entry.name(),
                "cost", String.valueOf(entry.cost()),
                "balance", String.valueOf(service.balance(player.getUniqueId()))));
        feedback.play(player);
    }
}
