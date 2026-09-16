package dev.smpeconomy.model;

import java.time.Instant;
import java.util.UUID;

public record Transaction(
    long    id,
    UUID    playerUuid,
    String  itemKey,
    int     quantity,
    double  pricePerUnit,
    double  multiplier,
    double  totalEarned,
    Source  source,
    Instant createdAt
) {
    public enum Source { SELL_HAND, SELL_INVENTORY, SELL_GUI, AUTOSELL, SPAWNER, ADMIN, SHOP_BUY, PLAYER_BUY, PLAYER_SELL }
}
