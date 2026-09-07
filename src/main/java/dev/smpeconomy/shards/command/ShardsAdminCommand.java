package dev.smpeconomy.shards.command;

import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.shards.ShardsModule;
import dev.smpeconomy.shards.service.ShardsService;
import dev.smpeconomy.shards.util.EarnFeedback;
import dev.smpeconomy.shards.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * give/take/set — run from console, this is how VotingPlugin, ExcellentCrates,
 * and future event plugins pay shards without a compile-time dependency.
 */
public final class ShardsAdminCommand implements CommandExecutor {

    private final ConfigManager config;
    private final ShardsService service;
    private final Msg msg;
    private final ShardsModule module;
    private final EarnFeedback feedback;

    public ShardsAdminCommand(ConfigManager config, ShardsService service, Msg msg,
                              ShardsModule module, EarnFeedback feedback) {
        this.config = config;
        this.service = service;
        this.msg = msg;
        this.module = module;
        this.feedback = feedback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            // Shares config.yml with the money economy, so this reloads both.
            config.reload();
            module.reload();
            msg.send(sender, "reloaded", Map.of());
            return true;
        }
        if (args.length < 3) {
            msg.send(sender, "admin-usage", Map.of());
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg.send(sender, "admin-player-online", Map.of("player", args[1]));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            msg.send(sender, "admin-usage", Map.of());
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> {
                long balance = service.deposit(target.getUniqueId(), amount);
                msg.sendActionBar(target, "actionbar-earned", Map.of("amount", String.valueOf(amount)));
                feedback.play(target);
                msg.send(sender, "admin-given", Map.of(
                        "amount", String.valueOf(amount),
                        "player", target.getName(),
                        "balance", String.valueOf(balance)));
            }
            case "take" -> {
                if (service.withdraw(target.getUniqueId(), amount)) {
                    msg.send(sender, "admin-taken", Map.of(
                            "amount", String.valueOf(amount),
                            "player", target.getName(),
                            "balance", String.valueOf(service.balance(target.getUniqueId()))));
                } else {
                    msg.send(sender, "admin-insufficient", Map.of(
                            "player", target.getName(),
                            "balance", String.valueOf(service.balance(target.getUniqueId()))));
                }
            }
            case "set" -> {
                long balance = service.set(target.getUniqueId(), amount);
                msg.send(sender, "admin-set", Map.of(
                        "player", target.getName(),
                        "balance", String.valueOf(balance)));
            }
            default -> msg.send(sender, "admin-usage", Map.of());
        }
        return true;
    }
}
