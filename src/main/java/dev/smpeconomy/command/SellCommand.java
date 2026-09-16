package dev.smpeconomy.command;

import dev.smpeconomy.message.Messages;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.gui.SellGui;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class SellCommand {

    private final Messages messages;

    public SellCommand(Messages messages) {
        this.messages = messages;
    }


    public void register(Commands registrar) {
        // /sell opens the drop-in sell GUI. The hand/inventory/all subcommands and
        // the /sellall alias were removed in favour of the GUI-only flow.
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("sell")
            .requires(src -> src.getSender().hasPermission("customeco.sell"))
            .executes(ctx -> {
                if (!(ctx.getSource().getSender() instanceof Player p)) {
                    ctx.getSource().getSender().sendMessage(
                        messages.get(CoreKeys.PLAYERS_ONLY));
                    return Command.SINGLE_SUCCESS;
                }
                new SellGui().open(p);
                return Command.SINGLE_SUCCESS;
            })
            .build();

        registrar.register(node, "Open the sell menu", List.of());
    }
}
