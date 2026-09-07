package dev.smpeconomy.model;

import java.time.Instant;
import java.util.UUID;

public record StorageItem(
    long    id,
    UUID    ownerUuid,
    byte[]  itemData,
    int     quantity,
    Instant createdAt
) {}
