package dev.smpeconomy.shards.command;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.shards.ShardsModule;
import dev.smpeconomy.shards.service.ShardsService;
import dev.smpeconomy.shards.util.EarnFeedback;
import dev.smpeconomy.message.ShardKeys;
import dev.smpeconomy.message.Messages;
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
    private final Messages msg;
    private final ShardsModule module;
    private final EarnFeedback feedback;

    public ShardsAdminCommand(ConfigManager config, ShardsService service, Messages msg,
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
            msg.sendPrefixed(sender, ShardKeys.RELOADED);
            return true;
        }
        if (args.length < 3) {
            msg.sendPrefixed(sender, ShardKeys.ADMIN_USAGE);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg.sendPrefixed(sender, ShardKeys.ADMIN_PLAYER_ONLINE, TokenBag.of().put("player", args[1]));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            msg.sendPrefixed(sender, ShardKeys.ADMIN_USAGE);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> {
                long balance = service.deposit(target.getUniqueId(), amount);
                msg.sendActionBar(target, ShardKeys.ACTIONBAR_EARNED, TokenBag.of().put("amount", String.valueOf(amount)));
                feedback.play(target);
                msg.sendPrefixed(sender, ShardKeys.ADMIN_GIVEN, TokenBag.of().put("amount", String.valueOf(amount)).put("player", target.getName()).put("balance", String.valueOf(balance)));
            }
            case "take" -> {
                if (service.withdraw(target.getUniqueId(), amount)) {
                    msg.sendPrefixed(sender, ShardKeys.ADMIN_TAKEN, TokenBag.of().put("amount", String.valueOf(amount)).put("player", target.getName()).put("balance", String.valueOf(service.balance(target.getUniqueId()))));
                } else {
                    msg.sendPrefixed(sender, ShardKeys.ADMIN_INSUFFICIENT, TokenBag.of().put("player", target.getName()).put("balance", String.valueOf(service.balance(target.getUniqueId()))));
                }
            }
            case "set" -> {
                long balance = service.set(target.getUniqueId(), amount);
                msg.sendPrefixed(sender, ShardKeys.ADMIN_SET, TokenBag.of().put("player", target.getName()).put("balance", String.valueOf(balance)));
            }
            default -> msg.sendPrefixed(sender, ShardKeys.ADMIN_USAGE);
        }
        return true;
    }
}
