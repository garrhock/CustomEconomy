package dev.smpeconomy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.gui.PlayerShopMenu;
import dev.smpeconomy.model.PlayerListing.Type;
import dev.smpeconomy.service.PlayerShopService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Quick-access commands for the player shop:
 *   /auctionhouse (/ah) → Sell Offers view
 *   /offers       (/o)  → Buy Orders view
 *
 * Both open the same {@link PlayerShopMenu}, just pre-set to a starting view.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PlayerShopCommand {

    private final ConfigManager config;
    private final PlayerShopService shopService;

    public PlayerShopCommand(ConfigManager config, PlayerShopService shopService) {
        this.config      = config;
        this.shopService = shopService;
    }

    public void register(Commands registrar) {
        LiteralCommandNode<CommandSourceStack> auction = Commands.literal("auctionhouse")
            .requires(src -> src.getSender().hasPermission("customeco.shop"))
            .executes(ctx -> open(ctx, Type.SELL))
            .build();
        registrar.register(auction, "Browse player sell offers", List.of("ah"));

        LiteralCommandNode<CommandSourceStack> offers = Commands.literal("offers")
            .requires(src -> src.getSender().hasPermission("customeco.shop"))
            .executes(ctx -> open(ctx, Type.BUY))
            .build();
        registrar.register(offers, "Browse player buy orders", List.of("o"));
    }

    private int open(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, Type view) {
        if (!(ctx.getSource().getSender() instanceof Player p)) {
            ctx.getSource().getSender().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        new PlayerShopMenu(config, shopService, view).open(p);
        return Command.SINGLE_SUCCESS;
    }
}
