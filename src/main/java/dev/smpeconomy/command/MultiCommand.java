package dev.smpeconomy.command;

import dev.smpeconomy.message.Messages;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.gui.MultiMainMenu;
import dev.smpeconomy.service.MultiplierService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class MultiCommand {

    private final MultiplierService multiplierService;
    private final Messages messages;

    public MultiCommand(MultiplierService multiplierService, Messages messages) {
        this.messages = messages;
        this.multiplierService = multiplierService;
    }

    public void register(Commands registrar) {
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("multi")
            .requires(src -> src.getSender().hasPermission("customeco.multi"))
            .executes(ctx -> {
                if (!(ctx.getSource().getSender() instanceof Player p)) {
                    ctx.getSource().getSender().sendMessage(
                        messages.get(CoreKeys.PLAYERS_ONLY));
                    return Command.SINGLE_SUCCESS;
                }
                new MultiMainMenu(multiplierService, p.getUniqueId()).open(p);
                return Command.SINGLE_SUCCESS;
            })
            .build();

        registrar.register(node, "View your sell multiplier progression",
            List.of("multiplier", "multipliers", "progression"));
    }
}
