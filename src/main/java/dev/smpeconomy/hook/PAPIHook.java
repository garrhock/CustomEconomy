package dev.smpeconomy.hook;

import dev.smpeconomy.CustomEconomy;
import dev.smpeconomy.model.ItemCategory;
import dev.smpeconomy.service.MultiplierService;
import dev.smpeconomy.service.WorthService;
import dev.smpeconomy.util.FormatUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * PlaceholderAPI expansion — identifier "customeco".
 *
 * Balance:
 *   %customeco_balance%               — formatted balance (e.g. $1.23K)
 *   %customeco_balance_raw%           — raw double
 *   %customeco_items_loaded%          — count of priced items
 *
 * Per-category multiplier progression:
 *   %customeco_multiplier_<category>% — e.g. %customeco_multiplier_ores%  → 1.50x
 *   %customeco_level_<category>%      — e.g. %customeco_level_mining%     → 7
 *   %customeco_xp_<category>%         — total accumulated XP for category
 *   %customeco_xp_next_<category>%    — XP needed to reach next level
 *
 * Valid category names: ores, mining, farming, mob_drops, woodcutting,
 *                       fishing, nether, crops, mob_essence, general
 */
public final class PAPIHook extends PlaceholderExpansion {

    private final CustomEconomy plugin;
    private final WorthService worthService;

    public PAPIHook(CustomEconomy plugin, WorthService worthService) {
        this.plugin       = plugin;
        this.worthService = worthService;
    }

    @Override public @NotNull String getIdentifier() { return "customeco"; }
    @Override public @NotNull String getAuthor()     { return "SMP"; }
    @Override public @NotNull String getVersion()    { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist()               { return true; }
    @Override public boolean canRegister()           { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        String sym = plugin.getConfigManager().getCurrencySymbol();
        MultiplierService ms = plugin.getMultiplierService();

        // ── Balance ───────────────────────────────────────────────────────────
        if (params.equals("balance")) {
            return FormatUtil.formatMoney(plugin.getVaultHook().getBalance(player), sym);
        }
        if (params.equals("balance_raw")) {
            return String.valueOf(plugin.getVaultHook().getBalance(player));
        }
        if (params.equals("items_loaded")) {
            return String.valueOf(worthService.getItemCount());
        }

        // ── Per-category progression ──────────────────────────────────────────
        UUID uuid = player.getUniqueId();

        if (params.startsWith("multiplier_")) {
            ItemCategory cat = ItemCategory.fromString(params.substring("multiplier_".length()));
            double mult = ms != null ? ms.getMultiplier(uuid, cat) : 1.0;
            return FormatUtil.formatMultiplier(mult);
        }
        if (params.startsWith("level_")) {
            ItemCategory cat = ItemCategory.fromString(params.substring("level_".length()));
            int level = ms != null ? ms.getLevel(uuid, cat) : 0;
            return String.valueOf(level);
        }
        if (params.startsWith("xp_next_")) {
            ItemCategory cat = ItemCategory.fromString(params.substring("xp_next_".length()));
            long xpNext = ms != null ? ms.getXpToNextLevel(uuid, cat) : 0L;
            return String.valueOf(xpNext);
        }
        if (params.startsWith("xp_")) {
            ItemCategory cat = ItemCategory.fromString(params.substring("xp_".length()));
            long xp = ms != null ? ms.getXp(uuid, cat) : 0L;
            return String.valueOf(xp);
        }

        return null;
    }
}
