package dev.smpeconomy.command;

import dev.smpeconomy.message.Messages;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.smpeconomy.config.ConfigManager;
import dev.smpeconomy.gui.ShopExploitValidator;
import dev.smpeconomy.model.ItemWorth;
import dev.smpeconomy.model.MarketPrice;
import dev.smpeconomy.service.MarketService;
import dev.smpeconomy.service.WorthService;
import dev.smpeconomy.shards.ShardsModule;
import dev.smpeconomy.util.FormatUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

@SuppressWarnings("UnstableApiUsage")
public final class EcoAdminCommand {

    private static final TextColor GOLD = TextColor.color(0xFFAA00);
    private static final TextColor AQUA = NamedTextColor.AQUA;
    private static final TextColor RED  = NamedTextColor.RED;
    private static final TextColor GREEN = NamedTextColor.GREEN;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final ConfigManager config;
    private final WorthService worthService;
    private final MarketService marketService;
    private final ShardsModule shards;

    public EcoAdminCommand(JavaPlugin plugin, ConfigManager config,
                           WorthService worthService, MarketService marketService,
                           ShardsModule shards, Messages messages) {
        this.messages = messages;
        this.plugin         = plugin;
        this.config         = config;
        this.worthService   = worthService;
        this.marketService  = marketService;
        this.shards         = shards;
    }

    public void register(Commands registrar) {
        LiteralCommandNode<CommandSourceStack> node = Commands.literal("ecoadmin")
            .requires(src -> src.getSender().hasPermission("customeco.admin"))

            // /ecoadmin reload
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    config.reload();
                    messages.reload();
                    shards.reload();
                    if (dev.smpeconomy.CustomEconomy.getInstance().getSpawnerModule() != null) {
                        // rates and messages apply now; [restart] keys only when tasks reschedule
                        dev.smpeconomy.CustomEconomy.getInstance().getSpawnerModule().reloadConfig();
                    }
                    var tooltips = dev.smpeconomy.CustomEconomy.getInstance().getWorthTooltipService();
                    if (tooltips != null) {
                        // new prices and a possibly new format — resend every open inventory
                        tooltips.refreshAll();
                    }
                    int issues = ShopExploitValidator.validate(worthService, config.getShopSections(), plugin.getLogger());
                    String msg = "CustomEconomy reloaded. Items: " + worthService.getItemCount()
                        + (issues > 0 ? " — WARNING: " + issues + " shop price exploit(s) detected, check console." : "");
                    ctx.getSource().getSender().sendMessage(
                        Component.text(msg, issues > 0 ? RED : GOLD));
                    return Command.SINGLE_SUCCESS;
                }))

            // /ecoadmin info
            .then(Commands.literal("info")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    sender.sendMessage(Component.text("── CustomEconomy Info ──", GOLD));
                    sender.sendMessage(Component.text("Items loaded: " + worthService.getItemCount(), AQUA));
                    sender.sendMessage(Component.text("Currency: " + config.getCurrencySymbol()
                        + " (" + config.getCurrencyName() + ")", AQUA));
                    sender.sendMessage(Component.text("Dynamic market: "
                        + (config.isMarketEnabled() ? "ENABLED" : "DISABLED"), AQUA));
                    sender.sendMessage(Component.text("Shards: "
                        + (shards.getService() == null ? "unavailable" : "enabled"), AQUA));
                    return Command.SINGLE_SUCCESS;
                }))

            // /ecoadmin worth <key>
            .then(Commands.literal("worth")
                .then(Commands.argument("item", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        worthService.getItemWorthMap().keySet().stream()
                            .filter(k -> k.startsWith(builder.getRemaining().toUpperCase()))
                            .limit(15).forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String key = StringArgumentType.getString(ctx, "item").toUpperCase();
                        ItemWorth w = worthService.getItemWorthMap().get(key);
                        CommandSender sender = ctx.getSource().getSender();
                        if (w == null) {
                            sender.sendMessage(Component.text("No price for: " + key, RED));
                        } else {
                            String sym = config.getCurrencySymbol();
                            sender.sendMessage(Component.text(
                                key + ": base=" + FormatUtil.formatMoney(w.getBasePrice(), sym)
                                + " min=" + FormatUtil.formatMoney(w.getMinPrice(), sym)
                                + " max=" + FormatUtil.formatMoney(w.getMaxPrice(), sym)
                                + " cat=" + w.getCategory().name(), AQUA));
                        }
                        return Command.SINGLE_SUCCESS;
                    })))

            // /ecoadmin debug
            .then(Commands.literal("debug")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    sender.sendMessage(Component.text("Items in price table: " + worthService.getItemCount(), AQUA));
                    sender.sendMessage(Component.text("Currency symbol: " + config.getCurrencySymbol(), AQUA));
                    sender.sendMessage(Component.text("Skip hotbar: " + config.isSkipHotbar(), AQUA));
                    return Command.SINGLE_SUCCESS;
                }))

            // /ecoadmin market <subcommand>
            .then(Commands.literal("market")
                .requires(src -> src.getSender().hasPermission("customeco.admin.market"))

                // /ecoadmin market info [item]
                .then(Commands.literal("info")
                    .executes(ctx -> {
                        // No item specified — print global market status
                        CommandSender sender = ctx.getSource().getSender();
                        sender.sendMessage(Component.text("── Market Status ──", GOLD));
                        sender.sendMessage(Component.text(
                            "Enabled: " + config.isMarketEnabled()
                            + "  |  Tick: " + config.getMarketTickIntervalSeconds() + "s"
                            + "  |  Window: " + config.getMarketRollingWindowHours() + "h"
                            + "  |  Baseline: " + (int) config.getMarketBaselineVolume() + " units", AQUA));
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            worthService.getItemWorthMap().keySet().stream()
                                .filter(k -> k.startsWith(builder.getRemaining().toUpperCase()))
                                .limit(15).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "item").toUpperCase(Locale.ROOT);
                            CommandSender sender = ctx.getSource().getSender();
                            MarketPrice mp = marketService.getSnapshot(key);
                            if (mp == null) {
                                sender.sendMessage(Component.text(key + " is not tracked by the market.", RED));
                                return Command.SINGLE_SUCCESS;
                            }
                            String sym = config.getCurrencySymbol();
                            double factor = mp.factor();
                            TextColor fc = factor > 1.01 ? RED : (factor < 0.99 ? GREEN : AQUA);
                            sender.sendMessage(Component.text("── Market: " + key + " ──", GOLD));
                            sender.sendMessage(Component.text(
                                "Base:    " + FormatUtil.formatMoney(mp.basePrice(), sym), AQUA));
                            sender.sendMessage(Component.text(
                                "Current: " + FormatUtil.formatMoney(mp.currentPrice(), sym)
                                + "  (" + marketService.getFactorDisplay(key) + ")", fc));
                            sender.sendMessage(Component.text(
                                "Frozen:  " + mp.priceFrozen()
                                + "  |  Updated: " + mp.lastUpdated(), AQUA));
                            return Command.SINGLE_SUCCESS;
                        })))

                // /ecoadmin market freeze <item>
                .then(Commands.literal("freeze")
                    .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            worthService.getItemWorthMap().keySet().stream()
                                .filter(k -> k.startsWith(builder.getRemaining().toUpperCase()))
                                .limit(15).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "item").toUpperCase(Locale.ROOT);
                            marketService.freezePrice(key);
                            ctx.getSource().getSender().sendMessage(
                                Component.text("Price frozen for " + key + ". It will no longer change with market ticks.", GOLD));
                            return Command.SINGLE_SUCCESS;
                        })))

                // /ecoadmin market unfreeze <item>
                .then(Commands.literal("unfreeze")
                    .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            worthService.getItemWorthMap().keySet().stream()
                                .filter(k -> k.startsWith(builder.getRemaining().toUpperCase()))
                                .limit(15).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "item").toUpperCase(Locale.ROOT);
                            marketService.unfreezePrice(key);
                            ctx.getSource().getSender().sendMessage(
                                Component.text("Price unfrozen for " + key + ". Market will now update it.", GOLD));
                            return Command.SINGLE_SUCCESS;
                        })))

                // /ecoadmin market reset [item|all]
                .then(Commands.literal("reset")
                    .then(Commands.literal("all")
                        .executes(ctx -> {
                            marketService.resetAll();
                            ctx.getSource().getSender().sendMessage(
                                Component.text("All non-frozen market prices reset to base.", GOLD));
                            return Command.SINGLE_SUCCESS;
                        }))
                    .then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            worthService.getItemWorthMap().keySet().stream()
                                .filter(k -> k.startsWith(builder.getRemaining().toUpperCase()))
                                .limit(15).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "item").toUpperCase(Locale.ROOT);
                            marketService.resetToBase(key);
                            ctx.getSource().getSender().sendMessage(
                                Component.text("Price for " + key + " reset to base.", GOLD));
                            return Command.SINGLE_SUCCESS;
                        }))))

            .build();

        registrar.register(node, "Admin commands for CustomEconomy", List.of("coadmin"));
    }
}
