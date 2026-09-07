package dev.smpeconomy.shards.papi;

import dev.smpeconomy.shards.service.ShardsService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** %shards_balance% */
public final class ShardsExpansion extends PlaceholderExpansion {

    private final ShardsService service;
    private final String version;

    public ShardsExpansion(ShardsService service, String version) {
        this.service = service;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "shards";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SMP";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        if (params.equalsIgnoreCase("balance")) {
            return String.valueOf(service.balance(player.getUniqueId()));
        }
        return null;
    }
}
