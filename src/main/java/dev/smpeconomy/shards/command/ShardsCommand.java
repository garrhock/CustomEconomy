package dev.smpeconomy.shards.command;

import dev.smpeconomy.shards.service.ShardsService;
import dev.smpeconomy.shards.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class ShardsCommand implements CommandExecutor {

    private final ShardsService service;
    private final Msg msg;

    public ShardsCommand(ShardsService service, Msg msg) {
        this.service = service;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "player-only", Map.of());
            return true;
        }
        msg.send(player, "balance",
                Map.of("balance", String.valueOf(service.balance(player.getUniqueId()))));
        return true;
    }
}
