package dev.smpeconomy.model;

import java.time.Instant;
import java.util.UUID;

public record PlayerListing(
    long    id,
    UUID    ownerUuid,
    String  ownerName,
    Type    listingType,
    String  itemKey,
    String  itemDisplayName,
    byte[]  itemData,
    int     quantityTotal,
    int     quantityFilled,
    double  pricePerUnit,
    Status  status,
    Instant createdAt,
    Instant expiresAt
) {
    public enum Type   { SELL, BUY }
    public enum Status { ACTIVE, FULFILLED, CANCELLED, EXPIRED }

    public int quantityRemaining() { return quantityTotal - quantityFilled; }
    public boolean isActive()      { return status == Status.ACTIVE; }
    public double totalValue()     { return pricePerUnit * quantityTotal; }
}
