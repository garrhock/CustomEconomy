package net.donutsmp.spawners.commands;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.SpawnerKeys;

import net.donutsmp.spawners.DonutSpawners;
import net.donutsmp.spawners.mob.SpawnerType;
import net.donutsmp.spawners.util.SpawnerItemUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

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
            sender.sendMessage(Component.text(
                    "Usage: /givespawner <player> <type> [stack]", NamedTextColor.RED));
            sendTypes(sender);
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }
        SpawnerType type = SpawnerType.fromString(args[1]);
        if (type == null) {
            plugin.messages().send(sender, SpawnerKeys.INVALID_TYPE);
            sendTypes(sender);
            return true;
        }
        int stack = 1;
        if (args.length > 2) {
            try {
                stack = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text(
                        "Stack size must be a number.", NamedTextColor.RED));
                return true;
            }
            if (stack < 1) {
                sender.sendMessage(Component.text(
                        "Stack size must be at least 1.", NamedTextColor.RED));
                return true;
            }
        }
        ItemStack item = SpawnerItemUtil.createSpawnerItem(type, stack);
        target.getInventory().addItem(item);
        plugin.messages().send(sender, SpawnerKeys.SPAWNER_GIVEN, TokenBag.of()
                .put("player", target.getName()));
        return true;
    }

    // One line rather than 70 — tab-complete is the real discovery path here.
    private static void sendTypes(CommandSender sender) {
        StringJoiner names = new StringJoiner(", ");
        for (SpawnerType type : SpawnerType.values()) {
            names.add(type.name().toLowerCase());
        }
        sender.sendMessage(Component.text("Types: ", NamedTextColor.GREEN)
                .append(Component.text(names.toString(), NamedTextColor.YELLOW)));
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
