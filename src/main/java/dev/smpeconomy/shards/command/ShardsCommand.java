package dev.smpeconomy.shards.command;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.shards.service.ShardsService;
import dev.smpeconomy.message.ShardKeys;
import dev.smpeconomy.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class ShardsCommand implements CommandExecutor {

    private final ShardsService service;
    private final Messages msg;

    public ShardsCommand(ShardsService service, Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg.sendPrefixed(sender, ShardKeys.PLAYER_ONLY);
            return true;
        }
        msg.sendPrefixed(player, ShardKeys.BALANCE, TokenBag.of().put("balance", String.valueOf(service.balance(player.getUniqueId()))));
        return true;
    }
}
