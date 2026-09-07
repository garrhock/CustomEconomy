package dev.smpeconomy.database.repository;

import dev.smpeconomy.database.DatabaseManager;
import dev.smpeconomy.model.MarketPrice;

import java.sql.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * All SQL against the market_prices table, plus rolling-window volume queries
 * against the transactions table.
 *
 * Blocking reads (findAll, volume) are intended for the dedicated DB thread inside
 * MarketService.  Write helpers (seed, updatePrices, setFrozen, resetToBase) are
 * also blocking and must be called off the main thread.
 */
public final class MarketRepository {

    public static final Executor DB_POOL =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CustomEco-DB-Market");
            t.setDaemon(true);
            return t;
        });

    private final DatabaseManager db;
    private final Logger log;
    private final boolean mysql;

    public MarketRepository(DatabaseManager db, boolean mysql) {
        this.db    = db;
        this.mysql = mysql;
        this.log   = Logger.getLogger("CustomEconomy");
    }

    // ── Seeding ───────────────────────────────────────────────────────────────

    /**
     * Inserts a row for {@code itemKey} only if it does not already exist.
     * Existing rows (with previously computed current_price) are preserved.
     */
    public void seed(String itemKey, double basePrice) {
        String sql = mysql
            ? "INSERT IGNORE INTO market_prices (item_key, base_price, current_price) VALUES (?, ?, ?)"
            : "INSERT OR IGNORE INTO market_prices (item_key, base_price, current_price) VALUES (?, ?, ?)";

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, itemKey.toUpperCase(java.util.Locale.ROOT));
            ps.setDouble(2, basePrice);
            ps.setDouble(3, basePrice); // current starts at base
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[Market] seed failed for " + itemKey + ": " + e.getMessage());
        }
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** Loads every row from market_prices into an item_key → MarketPrice map. */
    public Map<String, MarketPrice> findAll() {
        Map<String, MarketPrice> result = new HashMap<>();
        String sql = "SELECT item_key, base_price, current_price, total_sold, price_frozen, last_updated FROM market_prices";

        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString("item_key");
                Timestamp ts = rs.getTimestamp("last_updated");
                result.put(key, new MarketPrice(
                    key,
                    rs.getDouble("base_price"),
                    rs.getDouble("current_price"),
                    rs.getLong("total_sold"),
                    rs.getBoolean("price_frozen"),
                    ts != null ? ts.toInstant() : Instant.now()
                ));
            }
        } catch (SQLException e) {
            log.severe("[Market] findAll failed: " + e.getMessage());
        }
        return result;
    }

    // ── Volume queries ────────────────────────────────────────────────────────

    /**
     * Returns units SOLD per item_key since {@code since}.
     * Only counts sell-side transaction sources.
     */
    public Map<String, Long> getSellVolume(Instant since) {
        String sql = """
            SELECT item_key, SUM(quantity) AS vol
            FROM transactions
            WHERE created_at > ?
              AND source IN ('SELL_HAND', 'SELL_INVENTORY', 'SELL_GUI', 'AUTOSELL')
            GROUP BY item_key
            """;
        return queryVolume(sql, since);
    }

    /**
     * Returns units BOUGHT from the admin shop per item_key since {@code since}.
     */
    public Map<String, Long> getBuyVolume(Instant since) {
        String sql = """
            SELECT item_key, SUM(quantity) AS vol
            FROM transactions
            WHERE created_at > ?
              AND source = 'SHOP_BUY'
            GROUP BY item_key
            """;
        return queryVolume(sql, since);
    }

    /**
     * Number of distinct players who sold anything since {@code since}.
     * Drives adaptive market depth: more active sellers = deeper market.
     */
    public long getUniqueSellers(Instant since) {
        String sql = """
            SELECT COUNT(DISTINCT uuid) AS sellers
            FROM transactions
            WHERE created_at > ?
              AND source IN ('SELL_HAND', 'SELL_INVENTORY', 'SELL_GUI', 'AUTOSELL')
            """;
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("sellers");
            }
        } catch (SQLException e) {
            log.warning("[Market] unique-sellers query failed: " + e.getMessage());
        }
        return 0L;
    }

    private Map<String, Long> queryVolume(String sql, Instant since) {
        Map<String, Long> result = new HashMap<>();
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("item_key"), rs.getLong("vol"));
                }
            }
        } catch (SQLException e) {
            log.warning("[Market] volume query failed: " + e.getMessage());
        }
        return result;
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Batch-updates current_price for each item in {@code newPrices} (key → absolute price).
     * Skips frozen rows at the SQL level — if MarketService already filtered them,
     * this is a no-op for frozen items.
     */
    public void updatePrices(Map<String, Double> newPrices) {
        if (newPrices.isEmpty()) return;

        String sql = "UPDATE market_prices SET current_price = ?, last_updated = CURRENT_TIMESTAMP WHERE item_key = ? AND price_frozen = FALSE";

        try (Connection con = db.getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (Map.Entry<String, Double> e : newPrices.entrySet()) {
                    ps.setDouble(1, e.getValue());
                    ps.setString(2, e.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.severe("[Market] updatePrices batch failed: " + e.getMessage());
        }
    }

    /** Freezes or unfreezes a single item's price. */
    public void setFrozen(String itemKey, boolean frozen) {
        String sql = "UPDATE market_prices SET price_frozen = ? WHERE item_key = ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBoolean(1, frozen);
            ps.setString(2, itemKey.toUpperCase(java.util.Locale.ROOT));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[Market] setFrozen failed for " + itemKey + ": " + e.getMessage());
        }
    }

    /** Resets a single item's current_price back to its base_price. */
    public void resetToBase(String itemKey) {
        String sql = "UPDATE market_prices SET current_price = base_price, last_updated = CURRENT_TIMESTAMP WHERE item_key = ?";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, itemKey.toUpperCase(java.util.Locale.ROOT));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[Market] resetToBase failed for " + itemKey + ": " + e.getMessage());
        }
    }

    /** Resets every non-frozen item's current_price to its base_price. */
    public void resetAll() {
        String sql = "UPDATE market_prices SET current_price = base_price, last_updated = CURRENT_TIMESTAMP WHERE price_frozen = FALSE";
        try (Connection con = db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[Market] resetAll failed: " + e.getMessage());
        }
    }
}
