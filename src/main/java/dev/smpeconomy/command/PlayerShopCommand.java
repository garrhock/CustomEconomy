package dev.smpeconomy.command;

import dev.smpeconomy.message.TokenBag;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.gui.PlayerShopMenu;
import dev.smpeconomy.message.CoreKeys;
import dev.smpeconomy.message.Messages;
import dev.smpeconomy.model.PlayerListing;
import dev.smpeconomy.model.PlayerListing.Type;
import dev.smpeconomy.service.PlayerShopService;
import dev.smpeconomy.util.FormatUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Quick-access commands for the player shop:
 *   /auctionhouse (/ah) → Sell Offers view
 *   /offers       (/o)  → Buy Orders view
 *
 * Both open the same {@link PlayerShopMenu}, just pre-set to a starting view.
 *
 * Chat equivalents of the GUI flows:
 *   /ah sell <price> <quantity>  list the held item
 *   /ah list                     your active sell listings
 */
@SuppressWarnings("UnstableApiUsage")
public final class PlayerShopCommand {

    private final ConfigManager config;
    private final Messages messages;
    private final PlayerShopService shopService;

    public PlayerShopCommand(ConfigManager config, PlayerShopService shopService, Messages messages) {
        this.messages = messages;
        this.config      = config;
        this.shopService = shopService;
    }

    private Messages msg() {
        return messages;
    }

    public void register(Commands registrar) {
        LiteralCommandNode<CommandSourceStack> auction = Commands.literal("auctionhouse")
            .requires(src -> src.getSender().hasPermission("customeco.shop"))
            .executes(ctx -> open(ctx, Type.SELL))
            .then(Commands.literal("sell")
                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                    .then(Commands.argument("quantity", IntegerArgumentType.integer(1))
                        .executes(this::sell))))
            .then(Commands.literal("list")
                .executes(this::list))
            .build();
        registrar.register(auction, "Browse player sell offers", List.of("ah"));

        LiteralCommandNode<CommandSourceStack> offers = Commands.literal("offers")
            .requires(src -> src.getSender().hasPermission("customeco.shop"))
            .executes(ctx -> open(ctx, Type.BUY))
            .build();
        registrar.register(offers, "Browse player buy orders", List.of("o"));
    }

    private int open(CommandContext<CommandSourceStack> ctx, Type view) {
        Player p = player(ctx);
        if (p == null) return Command.SINGLE_SUCCESS;

        new PlayerShopMenu(config, shopService, view).open(p);
        return Command.SINGLE_SUCCESS;
    }

    /** Null if the sender isn't a player - they've already been told off by then. */
    private Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player p) return p;
        msg().send(ctx.getSource().getSender(), CoreKeys.PLAYERS_ONLY);
        return null;
    }

    private int sell(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) return Command.SINGLE_SUCCESS;

        double price    = DoubleArgumentType.getDouble(ctx, "price");
        int    quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        String sym      = config.getCurrencySymbol();

        // createSellListing() empties the stack before cloning it, so pass a snapshot
        ItemStack held = p.getInventory().getItemInMainHand().clone();
        if (held.getType().isAir()) {
            msg().send(p, CoreKeys.PLAYERSHOP_SELL_HOLD_ITEM);
            return Command.SINGLE_SUCCESS;
        }

        switch (shopService.createSellListing(p, held, quantity, price)) {
            case SUCCESS -> msg().send(p, CoreKeys.PLAYERSHOP_SELL_CREATED, TokenBag.of().put("quantity", quantity).put("item", held.getType().name().replace('_', ' ')).put("price", FormatUtil.formatMoney(price, sym)));
            case INSUFFICIENT_ITEMS -> msg().send(p, CoreKeys.PLAYERSHOP_SELL_NOT_ENOUGH);
            case MAX_LISTINGS_REACHED -> {
                int cap = shopService.getMaxListings(p);
                if (cap == Integer.MAX_VALUE) {
                    msg().send(p, CoreKeys.PLAYERSHOP_SELL_MAX_LISTINGS);
                } else {
                    msg().send(p, CoreKeys.PLAYERSHOP_SELL_MAX_LISTINGS_CAPPED, TokenBag.of().put("cap", cap));
                }
            }
            case PRICE_TOO_LOW -> msg().send(p, CoreKeys.PLAYERSHOP_SELL_PRICE_TOO_LOW, TokenBag.of().put("minimum", FormatUtil.formatMoney(shopService.getSellPriceFloor(held.getType()), sym)));
            default -> msg().send(p, CoreKeys.PLAYERSHOP_SELL_FAILED);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        Player p = player(ctx);
        if (p == null) return Command.SINGLE_SUCCESS;

        String sym = config.getCurrencySymbol();
        List<PlayerListing> active = shopService.getMyListings(p.getUniqueId(), Type.SELL)
            .stream().filter(PlayerListing::isActive).toList();

        if (active.isEmpty()) {
            msg().send(p, CoreKeys.PLAYERSHOP_LIST_EMPTY);
            return Command.SINGLE_SUCCESS;
        }

        msg().send(p, CoreKeys.PLAYERSHOP_LIST_HEADER);
        for (PlayerListing l : active) {
            String sold = l.quantityFilled() > 0
                ? msg().raw(CoreKeys.PLAYERSHOP_LIST_ENTRY_SOLD, TokenBag.of().put("quantity", l.quantityFilled()))
                : "";
            msg().send(p, CoreKeys.PLAYERSHOP_LIST_ENTRY, TokenBag.of().put("quantity", l.quantityRemaining()).put("item", l.itemDisplayName()).put("price", FormatUtil.formatMoney(l.pricePerUnit(), sym)).put("sold", sold));
        }
        return Command.SINGLE_SUCCESS;
    }
}
