package dev.smpeconomy.command;

import dev.smpeconomy.message.Messages;

import dev.smpeconomy.message.TokenBag;

import dev.smpeconomy.message.CoreKeys;

import dev.smpeconomy.CustomEconomy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.service.WorthService;
import dev.smpeconomy.util.FormatUtil;
import dev.smpeconomy.util.ItemUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;

@SuppressWarnings("UnstableApiUsage")
public final class WorthCommand {

    private static final TextColor GREEN = TextColor.color(0x55FF55);
    private static final TextColor GOLD  = TextColor.color(0xFFAA00);
    private static final TextColor GRAY  = NamedTextColor.GRAY;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final WorthService worthService;

    public WorthCommand(JavaPlugin plugin, WorthService worthService, Messages messages) {
        this.messages = messages;
        this.plugin       = plugin;
        this.worthService = worthService;
    }

    public void register(Commands registrar) {
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("worth")
            .requires(src -> src.getSender().hasPermission("customeco.worth"))
            .executes(ctx -> {
                // /worth — show held item worth
                if (!(ctx.getSource().getSender() instanceof Player p)) {
                    ctx.getSource().getSender().sendMessage(
                        messages.get(CoreKeys.PLAYERS_ONLY));
                    return Command.SINGLE_SUCCESS;
                }
                showHandWorth(p);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.argument("item", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> {
                    String input = builder.getRemaining().toUpperCase();
                    worthService.getItemWorthMap().keySet().stream()
                        .filter(k -> k.startsWith(input))
                        .limit(15)
                        .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    String key = StringArgumentType.getString(ctx, "item").toUpperCase();
                    ItemWorth worth = worthService.getItemWorthMap().get(key);
                    if (worth == null) {
                        ctx.getSource().getSender().sendMessage(
                            messages
                                .get(CoreKeys.WORTH_UNKNOWN_ITEM, TokenBag.of().put("item", key)));
                        return Command.SINGLE_SUCCESS;
                    }
                    String sym = plugin.getConfig().getString("economy.currency-symbol", "$");
                    // For a player, show what they'd actually earn (multiplier × market factor).
                    double unit = worth.getBasePrice();
                    boolean effective = false;
                    if (ctx.getSource().getSender() instanceof Player p) {
                        ItemStack built = ItemUtil.createFromKey(key);
                        if (built != null) {
                            double eff = worthService.getSellPrice(built, p);
                            if (eff >= 0) { unit = eff; effective = true; }
                        }
                    }
                    sendWorthMessage(ctx.getSource().getSender(), worth, 1, unit, effective, sym);
                    return Command.SINGLE_SUCCESS;
                }))
            .build();

        registrar.register(node, "Show item sell value", List.of("price", "value"));
    }

    private void showHandWorth(Player player) {
        String sym = plugin.getConfig().getString("economy.currency-symbol", "$");
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!ItemUtil.isValid(hand)) {
            player.sendMessage(messages
                .get(CoreKeys.WORTH_HOLD_ITEM));
            return;
        }
        Optional<ItemWorth> opt = worthService.getItemWorth(hand);
        if (opt.isEmpty()) {
            player.sendMessage(messages
                .get(CoreKeys.WORTH_NO_VALUE, TokenBag.of().put("item", ItemUtil.getDisplayName(hand))));
            return;
        }
        // Effective price the player would actually receive (multiplier × market factor).
        double unit = worthService.getSellPrice(hand, player);
        if (unit < 0) unit = opt.get().getBasePrice();
        sendWorthMessage(player, opt.get(), hand.getAmount(), unit, true, sym);
    }

    private void sendWorthMessage(org.bukkit.command.CommandSender sender, ItemWorth worth, int qty,
                                  double unitPrice, boolean effective, String sym) {
        double total = unitPrice * qty;
        sender.sendMessage(messages.get(CoreKeys.WORTH_HEADER, TokenBag.of().put("item", worth.getDisplayName()).put("category", worth.getCategory().getDisplayName())));
        sender.sendMessage(messages.get(effective ? CoreKeys.WORTH_UNIT_EFFECTIVE : CoreKeys.WORTH_UNIT_BASE, TokenBag.of().put("price", FormatUtil.formatMoney(unitPrice, sym))));
        if (qty > 1) {
            sender.sendMessage(messages.get(CoreKeys.WORTH_STACK, TokenBag.of().put("quantity", qty).put("total", FormatUtil.formatMoney(total, sym))));
        }
    }
}
