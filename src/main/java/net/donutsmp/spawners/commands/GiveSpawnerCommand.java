package net.donutsmp.spawners.commands;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.SpawnerKeys;

import net.donutsmp.spawners.DonutSpawners;
import net.donutsmp.spawners.mob.SpawnerType;
import net.donutsmp.spawners.util.SpawnerItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class GiveSpawnerCommand implements CommandExecutor, TabCompleter {
    private final DonutSpawners plugin;

    public GiveSpawnerCommand(DonutSpawners plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("donutspawners.admin")) {
            plugin.messages().send(sender, SpawnerKeys.NO_PERMISSION);
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("&cUsage: /givespawner <player> <type> [stack]");
            sender.sendMessage("&aAvailable types:");
            for (SpawnerType type : SpawnerType.values()) {
                sender.sendMessage("&e- " + type.name().toLowerCase() + " (" + type.getDisplayName() + ")");
            }
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("&cPlayer not found.");
            return true;
        }
        SpawnerType type = SpawnerType.fromString(args[1]);
        if (type == null) {
            plugin.messages().send(sender, SpawnerKeys.INVALID_TYPE);
            sender.sendMessage("&aAvailable types:");
            for (SpawnerType type2 : SpawnerType.values()) {
                sender.sendMessage("&e- " + type2.name().toLowerCase() + " (" + type2.getDisplayName() + ")");
            }
            return true;
        }
        int stack = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        ItemStack item = SpawnerItemUtil.createSpawnerItem(type, stack);
        target.getInventory().addItem(item);
        plugin.messages().send(sender, SpawnerKeys.SPAWNER_GIVEN, TokenBag.of()
                .put("player", target.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2) {
            for (SpawnerType type : SpawnerType.values()) {
                if (type.name().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(type.name().toLowerCase());
                }
            }
        }
        return completions;
    }
}
