package dev.smpeconomy.database.repository;

import dev.smpeconomy.database.DatabaseManager;
import dev.smpeconomy.model.PlayerListing;
import dev.smpeconomy.model.PlayerListing.Status;
import dev.smpeconomy.model.PlayerListing.Type;
import dev.smpeconomy.model.StorageItem;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * All SQL for player_listings and player_storage.
 * All methods are blocking and must be called off the main thread,
 * except for the small single-row writes used inside transactions
 * (buyFromListing, fulfillBuyOrder) which are dispatched via Bukkit
 * scheduler from the main thread.
 */
public final class PlayerShopRepository {

    private static final int PAGE_SIZE = 36;

    private final DatabaseManager db;
    private final Logger log;

    public PlayerShopRepository(DatabaseManager db) {
        this.db  = db;
        this.log = Logger.getLogger("CustomEconomy");
    }

    // ── Listings ──────────────────────────────────────────────────────────────

    /** Inserts a new listing and returns its generated ID, or -1 on failure. */
    public long createListing(PlayerListing listing) {
        String sql = """
            INSERT INTO player_listings
                (owner_uuid, owner_name, listing_type, item_key, item_display_name,
                 item_data, quantity_total, price_per_unit, expires_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """;
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, listing.ownerUuid().toString());
            ps.setString(2, listing.ownerName());
            ps.setString(3, listing.listingType().name());
            ps.setString(4, listing.itemKey());
            ps.setString(5, listing.itemDisplayName());
            ps.setBytes(6, listing.itemData());
            ps.setInt(7, listing.quantityTotal());
            ps.setDouble(8, listing.pricePerUnit());
            ps.setTimestamp(9, Timestamp.from(listing.expiresAt()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (SQLException e) {
            log.severe("[PlayerShop] createListing failed: " + e.getMessage());
            return -1L;
        }
    }

    /** Returns a page of ACTIVE listings of the given type, ordered by the given sort. */
    public List<PlayerListing> findActive(Type type, SortOrder sort, int page) {
        String order = switch (sort) {
            case PRICE_HIGH  -> "price_per_unit DESC";
            case PRICE_LOW   -> "price_per_unit ASC";
            case QTY_HIGH    -> "(quantity_total - quantity_filled) DESC";
            case QTY_LOW     -> "(quantity_total - quantity_filled) ASC";
            case NEWEST      -> "created_at DESC";
            case OLDEST      -> "created_at ASC";
            case ALPHABETICAL-> "item_display_name ASC";
        };
        String sql = "SELECT * FROM player_listings WHERE status = 'ACTIVE' AND listing_type = ? ORDER BY "
                     + order + " LIMIT ? OFFSET ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setInt(2, PAGE_SIZE);
            ps.setInt(3, page * PAGE_SIZE);
            return readListings(ps.executeQuery());
        } catch (SQLException e) {
            log.severe("[PlayerShop] findActive failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Returns all listings (any status) owned by a player, of the given type. */
    public List<PlayerListing> findByOwner(UUID ownerUuid, Type type) {
        String sql = "SELECT * FROM player_listings WHERE owner_uuid = ? AND listing_type = ? ORDER BY created_at DESC";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, type.name());
            return readListings(ps.executeQuery());
        } catch (SQLException e) {
            log.severe("[PlayerShop] findByOwner failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Finds a single listing by ID. Returns null if not found. */
    public PlayerListing findById(long id) {
        String sql = "SELECT * FROM player_listings WHERE id = ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            List<PlayerListing> results = readListings(ps.executeQuery());
            return results.isEmpty() ? null : results.get(0);
        } catch (SQLException e) {
            log.severe("[PlayerShop] findById failed: " + e.getMessage());
            return null;
        }
    }

    /** Returns count of ACTIVE listings for a player. */
    public int countActiveByOwner(UUID ownerUuid) {
        String sql = "SELECT COUNT(*) FROM player_listings WHERE owner_uuid = ? AND status = 'ACTIVE'";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private static final String SQL_FILL = """
        UPDATE player_listings
        SET quantity_filled = quantity_filled + ?
        WHERE id = ? AND status = 'ACTIVE'
          AND quantity_filled + ? <= quantity_total
        """;
    private static final String SQL_COMPLETE = """
        UPDATE player_listings SET status = 'FULFILLED'
        WHERE id = ? AND status = 'ACTIVE' AND quantity_filled >= quantity_total
        """;
    private static final String SQL_INSERT_STORAGE =
        "INSERT INTO player_storage (owner_uuid, item_data, quantity, listing_id) VALUES (?,?,?,?)";

    /**
     * Atomically reserves {@code qty} units of a SELL listing and marks it
     * FULFILLED if now complete — both in one transaction.  Used when the buyer
     * receives the items directly into their inventory (no storage row needed).
     *
     * @return true if the fill committed (listing still active with room)
     */
    public boolean fillListing(long listingId, int qty) {
        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);
            try {
                boolean filled;
                try (PreparedStatement ps = con.prepareStatement(SQL_FILL)) {
                    ps.setInt(1, qty); ps.setLong(2, listingId); ps.setInt(3, qty);
                    filled = ps.executeUpdate() > 0;
                }
                if (filled) {
                    try (PreparedStatement ps = con.prepareStatement(SQL_COMPLETE)) {
                        ps.setLong(1, listingId);
                        ps.executeUpdate();
                    }
                }
                con.commit();
                return filled;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.severe("[PlayerShop] fillListing failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Atomically reserves {@code qty} units of a BUY order, deposits the supplied
     * items into the order owner's claim storage, and marks the order FULFILLED if
     * now complete — all in one transaction.  Either every step commits or none do.
     *
     * @return true if the fill committed
     */
    public boolean fillBuyOrderWithStorage(long listingId, int qty, UUID owner, byte[] itemData) {
        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);
            try {
                boolean filled;
                try (PreparedStatement ps = con.prepareStatement(SQL_FILL)) {
                    ps.setInt(1, qty); ps.setLong(2, listingId); ps.setInt(3, qty);
                    filled = ps.executeUpdate() > 0;
                }
                if (!filled) {
                    con.rollback();
                    return false;
                }
                try (PreparedStatement ps = con.prepareStatement(SQL_INSERT_STORAGE)) {
                    ps.setString(1, owner.toString());
                    ps.setBytes(2, itemData);
                    ps.setInt(3, qty);
                    ps.setObject(4, listingId, java.sql.Types.BIGINT);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement(SQL_COMPLETE)) {
                    ps.setLong(1, listingId);
                    ps.executeUpdate();
                }
                con.commit();
                return true;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.severe("[PlayerShop] fillBuyOrderWithStorage failed: " + e.getMessage());
            return false;
        }
    }

    /** Outcome of {@link #closeListing}: whether this call closed it, and any Vault refund owed. */
    public record CloseResult(boolean closed, UUID owner, double refund) {
        static CloseResult notClosed() { return new CloseResult(false, null, 0); }
    }

    /**
     * Atomically transitions an ACTIVE listing to {@code newStatus}.  Reads the
     * remaining quantity inside the same transaction so the amount returned/refunded
     * is consistent.  For SELL listings the unfilled items are returned to the
     * owner's storage in the same transaction; for BUY orders the unfilled escrow
     * amount is reported back so the caller can refund via Vault (on the main thread).
     *
     * The {@code status = 'ACTIVE'} guard makes this idempotent: a cancel/expiry
     * race can only close — and therefore only return/refund — once.
     *
     * @return a {@link CloseResult}; {@code closed=false} if the listing was already
     *         closed by someone else (no items returned, no refund owed)
     */
    public CloseResult closeListing(long listingId, Status newStatus) {
        String select = """
            SELECT owner_uuid, listing_type, item_data, price_per_unit,
                   quantity_total - quantity_filled AS remaining
            FROM player_listings WHERE id = ? AND status = 'ACTIVE'
            """;
        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);
            try {
                UUID owner;
                Type type;
                byte[] data;
                double price;
                int remaining;
                try (PreparedStatement ps = con.prepareStatement(select)) {
                    ps.setLong(1, listingId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            con.rollback();
                            return CloseResult.notClosed();
                        }
                        owner     = UUID.fromString(rs.getString("owner_uuid"));
                        type      = Type.valueOf(rs.getString("listing_type"));
                        data      = rs.getBytes("item_data");
                        price     = rs.getDouble("price_per_unit");
                        remaining = rs.getInt("remaining");
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE player_listings SET status = ? WHERE id = ? AND status = 'ACTIVE'")) {
                    ps.setString(1, newStatus.name());
                    ps.setLong(2, listingId);
                    ps.executeUpdate();
                }

                double refund = 0;
                if (type == Type.SELL) {
                    if (remaining > 0) {
                        try (PreparedStatement ps = con.prepareStatement(SQL_INSERT_STORAGE)) {
                            ps.setString(1, owner.toString());
                            ps.setBytes(2, data);
                            ps.setInt(3, remaining);
                            ps.setObject(4, listingId, java.sql.Types.BIGINT);
                            ps.executeUpdate();
                        }
                    }
                } else {
                    refund = price * remaining;
                }

                con.commit();
                return new CloseResult(true, owner, refund);
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.severe("[PlayerShop] closeListing failed: " + e.getMessage());
            return CloseResult.notClosed();
        }
    }

    /** Returns all ACTIVE listings that have passed their expiry timestamp. */
    public List<PlayerListing> findExpired() {
        String sql = "SELECT * FROM player_listings WHERE status = 'ACTIVE' AND expires_at < CURRENT_TIMESTAMP";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            return readListings(ps.executeQuery());
        } catch (SQLException e) {
            log.severe("[PlayerShop] findExpired failed: " + e.getMessage());
            return List.of();
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────────
    // (Inserts into player_storage happen inside the transactional fill/close
    //  methods above so they commit atomically with the listing state change.)

    /** Returns a page of storage items for a player. */
    public List<StorageItem> getStorage(UUID ownerUuid, int page) {
        String sql = "SELECT * FROM player_storage WHERE owner_uuid = ? ORDER BY created_at ASC LIMIT ? OFFSET ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setInt(2, PAGE_SIZE);
            ps.setInt(3, page * PAGE_SIZE);
            return readStorage(ps.executeQuery());
        } catch (SQLException e) {
            log.severe("[PlayerShop] getStorage failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Returns total number of storage rows for a player (for pagination). */
    public int countStorage(UUID ownerUuid) {
        String sql = "SELECT COUNT(*) FROM player_storage WHERE owner_uuid = ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Returns true if any claim-storage rows remain for the given listing. */
    public boolean hasRemainingStorage(long listingId) {
        String sql = "SELECT 1 FROM player_storage WHERE listing_id = ? LIMIT 1";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.severe("[PlayerShop] hasRemainingStorage failed: " + e.getMessage());
            return true; // fail-safe: keep the listing visible rather than hide unclaimed items
        }
    }

    /** Returns all storage rows produced by a single listing (for direct claim). */
    public List<StorageItem> getStorageForListing(long listingId) {
        String sql = "SELECT * FROM player_storage WHERE listing_id = ? ORDER BY created_at ASC";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            return readStorage(ps.executeQuery());
        } catch (SQLException e) {
            log.severe("[PlayerShop] getStorageForListing failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Returns ALL storage items for a player (used by claim-all). */
    public List<StorageItem> getAllStorage(UUID ownerUuid) {
        String sql = "SELECT * FROM player_storage WHERE owner_uuid = ? ORDER BY created_at ASC";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            return readStorage(ps.executeQuery());
        } catch (SQLException e) {
            log.severe("[PlayerShop] getAllStorage failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Deletes a single storage entry by ID. */
    public void removeStorage(long storageId) {
        String sql = "DELETE FROM player_storage WHERE id = ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, storageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("[PlayerShop] removeStorage failed: " + e.getMessage());
        }
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private List<PlayerListing> readListings(ResultSet rs) throws SQLException {
        List<PlayerListing> list = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                Timestamp created = rs.getTimestamp("created_at");
                Timestamp expires = rs.getTimestamp("expires_at");
                list.add(new PlayerListing(
                    rs.getLong("id"),
                    UUID.fromString(rs.getString("owner_uuid")),
                    rs.getString("owner_name"),
                    Type.valueOf(rs.getString("listing_type")),
                    rs.getString("item_key"),
                    rs.getString("item_display_name"),
                    rs.getBytes("item_data"),
                    rs.getInt("quantity_total"),
                    rs.getInt("quantity_filled"),
                    rs.getDouble("price_per_unit"),
                    Status.valueOf(rs.getString("status")),
                    created != null ? created.toInstant() : Instant.now(),
                    expires != null ? expires.toInstant() : Instant.now()
                ));
            }
        }
        return list;
    }

    private List<StorageItem> readStorage(ResultSet rs) throws SQLException {
        List<StorageItem> list = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                Timestamp created = rs.getTimestamp("created_at");
                list.add(new StorageItem(
                    rs.getLong("id"),
                    UUID.fromString(rs.getString("owner_uuid")),
                    rs.getBytes("item_data"),
                    rs.getInt("quantity"),
                    created != null ? created.toInstant() : Instant.now()
                ));
            }
        }
        return list;
    }

    // ── Sort order ────────────────────────────────────────────────────────────

    public enum SortOrder {
        PRICE_HIGH, PRICE_LOW,
        QTY_HIGH, QTY_LOW,
        NEWEST, OLDEST,
        ALPHABETICAL
    }
}
