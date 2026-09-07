package dev.smpeconomy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.gui.ShopMainMenu;
import dev.smpeconomy.service.MarketService;
import dev.smpeconomy.service.ShopService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class ShopCommand {

    private final ConfigManager config;
    private final ShopService shopService;
    private final MarketService marketService;

    public ShopCommand(ConfigManager config, ShopService shopService, MarketService marketService) {
        this.config        = config;
        this.shopService   = shopService;
        this.marketService = marketService;
    }

    public void register(Commands registrar) {
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("shop")
            .requires(src -> src.getSender().hasPermission("customeco.shop"))
            .executes(ctx -> {
                if (!(ctx.getSource().getSender() instanceof Player p)) {
                    ctx.getSource().getSender().sendMessage(
                        Component.text("Players only.", NamedTextColor.RED));
                    return Command.SINGLE_SUCCESS;
                }
                new ShopMainMenu(config, shopService, marketService).open(p);
                return Command.SINGLE_SUCCESS;
            })
            .build();

        registrar.register(node, "Browse and buy items from the shop", List.of("store", "buy"));
    }
}
