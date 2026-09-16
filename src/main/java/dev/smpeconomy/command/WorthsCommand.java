package dev.smpeconomy.command;

import dev.smpeconomy.message.Messages;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.gui.WorthsMenu;
import dev.smpeconomy.service.MarketService;
import dev.smpeconomy.service.WorthService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /worths — opens the sellable-item value catalog GUI.
 */
@SuppressWarnings("UnstableApiUsage")
public final class WorthsCommand {

    private final ConfigManager config;
    private final Messages messages;
    private final WorthService worthService;
    private final MarketService marketService;

    public WorthsCommand(ConfigManager config, WorthService worthService, MarketService marketService, Messages messages) {
        this.messages = messages;
        this.config        = config;
        this.worthService  = worthService;
        this.marketService = marketService;
    }

    public void register(Commands registrar) {
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("worths")
            .requires(src -> src.getSender().hasPermission("customeco.worth"))
            .executes(ctx -> {
                if (!(ctx.getSource().getSender() instanceof Player p)) {
                    ctx.getSource().getSender().sendMessage(
                        messages.get(CoreKeys.PLAYERS_ONLY));
                    return Command.SINGLE_SUCCESS;
                }
                new WorthsMenu(config, worthService, marketService).open(p);
                return Command.SINGLE_SUCCESS;
            })
            .build();

        registrar.register(node, "Browse the value of every sellable item", List.of());
    }
}
